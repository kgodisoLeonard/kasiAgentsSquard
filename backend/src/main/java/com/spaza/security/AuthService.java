package com.spaza.security;

import com.spaza.audit.AuditLog;
import com.spaza.audit.AuditLog.ActorType;
import com.spaza.model.AppUser;
import com.spaza.repository.AppUserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/**
 * Sessions, MFA and step-up verification.
 * REAL: bcrypt password check, account lockout, random one-time codes stored only as hashes with expiry and
 * attempt limits, opaque random access tokens (15 min) stored as SHA-256 hashes, refresh-token rotation with
 * reuse detection, remote log-out, single-use step-up grants.
 * SIMULATED: OTP delivery. No SMS provider is connected, so the code is returned to the client and shown
 * on screen labelled "simulated SMS". Sessions are held in memory and end when the server restarts.
 */
@Service
public class AuthService {
  public record AuthUser(String id, String name, String email, String role, int level, String sessionId) {}
  public static class AuthException extends RuntimeException {
    final int status;
    public AuthException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
  }

  static final Duration ACCESS_TTL = Duration.ofMinutes(15), REFRESH_TTL = Duration.ofHours(8), OTP_TTL = Duration.ofMinutes(5),
      STEP_UP_TTL = Duration.ofMinutes(5), LOCKOUT = Duration.ofMinutes(15);
  static final int MAX_FAILURES = 5, MAX_OTP_ATTEMPTS = 5;

  private record Challenge(String userId, String codeHash, Instant expires, String purpose, int[] attempts) {}
  private record Session(String id, String userId, String deviceId, Instant created, Instant accessExpires, String accessHash, String refreshHash, Instant refreshExpires) {}
  private record Grant(String userId, String action, Instant expires) {}

  private final AppUserRepository users;
  private final AuditLog audit;
  private final Clock clock;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final SecureRandom random = new SecureRandom();
  private final Map<String,int[]> failures = new HashMap<>();
  private final Map<String,Instant> lockedUntil = new HashMap<>();
  private final Map<String,Challenge> challenges = new HashMap<>();
  private final Map<String,Session> sessionsById = new HashMap<>();
  private final Map<String,String> sessionByAccess = new HashMap<>(), sessionByRefresh = new HashMap<>(), retiredRefresh = new HashMap<>();
  private final Map<String,Set<String>> knownDevices = new HashMap<>();
  private final Map<String,Grant> grants = new HashMap<>();
  private final Map<String,List<Map<String,Object>>> securityAlerts = new HashMap<>();

  public AuthService(AppUserRepository users, AuditLog audit, Clock clock) { this.users = users; this.audit = audit; this.clock = clock; }

  // ------------------------------------------------------------- login + MFA

  public synchronized Map<String,Object> startLogin(String email, String password, String ip) {
    Instant now = clock.instant();
    Instant until = lockedUntil.get(email);
    if (until != null && now.isBefore(until)) {
      long mins = Math.max(1, Duration.between(now, until).toMinutes() + 1);
      throw new AuthException(429, "Too many failed sign-in attempts. Try again in " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
    }
    Optional<AppUser> user = users.findByEmail(email).filter(u -> encoder.matches(password, u.getPasswordHash()));
    if (user.isEmpty()) {
      int count = ++failures.computeIfAbsent(email, k -> new int[1])[0];
      audit.record(ActorType.HUMAN, email, "LOGIN_FAILED", Map.of("ip", ip, "failures", count), Map.of());
      if (count >= MAX_FAILURES) {
        lockedUntil.put(email, now.plus(LOCKOUT)); failures.remove(email);
        audit.record(ActorType.SYSTEM, "auth", "ACCOUNT_LOCKED", Map.of("email", email), Map.of("minutes", LOCKOUT.toMinutes()));
        throw new AuthException(429, "Too many failed sign-in attempts. The account is locked for " + LOCKOUT.toMinutes() + " minutes.");
      }
      throw new AuthException(401, "Invalid email or password");
    }
    failures.remove(email);
    return issueChallenge(user.get().getId(), "LOGIN");
  }

  public synchronized Map<String,Object> completeLogin(String challengeId, String code, String deviceId, String ip) {
    Challenge c = consumeChallenge(challengeId, code, "LOGIN");
    AppUser user = users.findById(c.userId()).orElseThrow(() -> new AuthException(401, "Account no longer exists"));
    String device = deviceId == null || deviceId.isBlank() ? "unknown-device" : deviceId.trim();
    String deviceHash = AuditLog.sha256(device);
    boolean newDevice = knownDevices.computeIfAbsent(user.getId(), k -> new HashSet<>()).add(deviceHash) && knownDevices.get(user.getId()).size() > 1;
    if (newDevice) alert(user.getId(), "NEW_DEVICE_SIGN_IN", "A new device signed in to your account. If this was not you, sign out all devices.");
    Map<String,Object> tokens = openSession(user.getId(), deviceHash);
    audit.record(ActorType.HUMAN, user.getId(), "LOGIN_SUCCEEDED", Map.of("ip", ip, "mfa", "OTP (simulated delivery)", "newDevice", newDevice), Map.of("sessionId", tokens.get("sessionId")));
    Map<String,Object> result = new LinkedHashMap<>(tokens); result.put("user", userView(user)); result.put("newDevice", newDevice);
    return result;
  }

  public synchronized Map<String,Object> refresh(String refreshToken) {
    String hash = AuditLog.sha256(String.valueOf(refreshToken));
    String sessionId = sessionByRefresh.get(hash);
    if (sessionId == null) {
      String stolen = retiredRefresh.get(hash);
      if (stolen != null) {
        // A rotated refresh token was used again: assume theft and end every session for that user.
        Session s = sessionsById.get(stolen);
        String userId = s == null ? stolen : s.userId();
        revokeAll(userId, "REFRESH_TOKEN_REUSE");
        alert(userId, "SESSION_REVOKED", "An old sign-in token was reused, so all your sessions were signed out for safety.");
      }
      throw new AuthException(401, "Session expired. Please sign in again.");
    }
    Session s = sessionsById.get(sessionId);
    if (s == null || clock.instant().isAfter(s.refreshExpires())) { revoke(sessionId); throw new AuthException(401, "Session expired. Please sign in again."); }
    revoke(sessionId);
    retiredRefresh.put(hash, s.userId());
    Map<String,Object> tokens = openSession(s.userId(), s.deviceId());
    users.findById(s.userId()).ifPresent(u -> tokens.put("user", userView(u)));
    return tokens;
  }

  public synchronized AuthUser authenticate(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) return null;
    String sessionId = sessionByAccess.get(AuditLog.sha256(accessToken));
    Session s = sessionId == null ? null : sessionsById.get(sessionId);
    if (s == null || clock.instant().isAfter(s.accessExpires())) return null;
    AppUser u = users.findById(s.userId()).orElse(null);
    if (u == null) { revoke(sessionId); return null; }
    return new AuthUser(u.getId(), u.getName(), u.getEmail(), u.getRole(), level(u), s.id());
  }

  public synchronized void logout(AuthUser user) {
    revoke(user.sessionId());
    audit.record(ActorType.HUMAN, user.id(), "LOGOUT", Map.of("sessionId", user.sessionId()), Map.of());
  }

  public synchronized int logoutEverywhere(AuthUser user) {
    int n = revokeAll(user.id(), "REMOTE_LOGOUT");
    audit.record(ActorType.HUMAN, user.id(), "LOGOUT_ALL_DEVICES", Map.of(), Map.of("sessionsEnded", n));
    return n;
  }

  public synchronized List<Map<String,Object>> sessions(AuthUser user) {
    return sessionsById.values().stream().filter(s -> s.userId().equals(user.id())).map(s -> Map.<String,Object>of(
        "id", s.id(), "device", s.deviceId().substring(0, 10), "signedInAt", s.created().toString(), "current", s.id().equals(user.sessionId()))).toList();
  }

  public synchronized List<Map<String,Object>> alerts(String userId) { return List.copyOf(securityAlerts.getOrDefault(userId, List.of())); }

  // ------------------------------------------------------------- step-up

  public synchronized Map<String,Object> startStepUp(AuthUser user, String action) {
    if (action == null || action.isBlank()) throw new IllegalArgumentException("Action is required");
    return issueChallenge(user.id(), "STEP_UP:" + action.trim());
  }

  public synchronized Map<String,Object> completeStepUp(AuthUser user, String challengeId, String code) {
    Challenge peek = challenges.get(challengeId);
    if (peek == null || !peek.userId().equals(user.id()) || !peek.purpose().startsWith("STEP_UP:")) throw new AuthException(401, "Verification code expired. Request a new one.");
    Challenge c = consumeChallenge(challengeId, code, peek.purpose());
    String action = c.purpose().substring("STEP_UP:".length());
    String token = token();
    grants.put(AuditLog.sha256(token), new Grant(user.id(), action, clock.instant().plus(STEP_UP_TTL)));
    audit.record(ActorType.HUMAN, user.id(), "STEP_UP_VERIFIED", Map.of("action", action), Map.of("validMinutes", STEP_UP_TTL.toMinutes()));
    return Map.of("stepUpToken", token, "action", action, "expiresAt", clock.instant().plus(STEP_UP_TTL).toString());
  }

  /** Consumes a single-use step-up grant. Throws 403 with code STEP_UP_REQUIRED when missing or invalid. */
  public synchronized void requireStepUp(AuthUser user, String action, String stepUpToken) {
    Grant g = stepUpToken == null ? null : grants.remove(AuditLog.sha256(stepUpToken));
    if (g == null || !g.userId().equals(user.id()) || !g.action().equals(action) || clock.instant().isAfter(g.expires()))
      throw new StepUpRequired(action);
  }

  public static class StepUpRequired extends RuntimeException {
    final String action;
    public StepUpRequired(String action) { super("Extra verification is required for: " + action); this.action = action; }
    public String action() { return action; }
  }

  // ------------------------------------------------------------- helpers

  public static int level(AppUser u) { return u.isVerified() ? 2 : 1; }

  public static Map<String,Object> userView(AppUser u) {
    return Map.of("id", u.getId(), "name", u.getName(), "email", u.getEmail(), "role", u.getRole(), "shop", u.getBusinessName(),
        "verified", u.isVerified(), "level", level(u), "levelLabel", u.isVerified() ? "Level 2 · Verified" : "Level 1 · Basic");
  }

  private Map<String,Object> issueChallenge(String userId, String purpose) {
    challenges.values().removeIf(c -> clock.instant().isAfter(c.expires()));
    String id = token();
    String code = String.format("%06d", random.nextInt(1_000_000));
    challenges.put(id, new Challenge(userId, AuditLog.sha256(id + ":" + code), clock.instant().plus(OTP_TTL), purpose, new int[1]));
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("mfaRequired", true); r.put("challengeId", id); r.put("purpose", purpose); r.put("expiresAt", clock.instant().plus(OTP_TTL).toString());
    r.put("delivery", "SIMULATED_SMS"); r.put("demoCode", code);
    r.put("notice", "SIMULATED: no SMS provider is connected, so the one-time code is shown on screen for the demo.");
    return r;
  }

  private Challenge consumeChallenge(String challengeId, String code, String purpose) {
    Challenge c = challengeId == null ? null : challenges.get(challengeId);
    if (c == null || !c.purpose().equals(purpose) || clock.instant().isAfter(c.expires())) { if (c != null) challenges.remove(challengeId); throw new AuthException(401, "Verification code expired. Request a new one."); }
    if (!AuditLog.sha256(challengeId + ":" + String.valueOf(code).trim()).equals(c.codeHash())) {
      if (++c.attempts()[0] >= MAX_OTP_ATTEMPTS) { challenges.remove(challengeId); throw new AuthException(429, "Too many wrong codes. Start again."); }
      throw new AuthException(401, "Incorrect verification code");
    }
    challenges.remove(challengeId);
    return c;
  }

  private Map<String,Object> openSession(String userId, String deviceHash) {
    String access = token(), refresh = token(), id = UUID.randomUUID().toString();
    Instant now = clock.instant();
    Session s = new Session(id, userId, deviceHash, now, now.plus(ACCESS_TTL), AuditLog.sha256(access), AuditLog.sha256(refresh), now.plus(REFRESH_TTL));
    sessionsById.put(id, s); sessionByAccess.put(s.accessHash(), id); sessionByRefresh.put(s.refreshHash(), id);
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("accessToken", access); r.put("refreshToken", refresh); r.put("expiresIn", ACCESS_TTL.toSeconds()); r.put("sessionId", id);
    return r;
  }

  private void revoke(String sessionId) {
    Session s = sessionsById.remove(sessionId);
    if (s != null) { sessionByAccess.remove(s.accessHash()); sessionByRefresh.remove(s.refreshHash()); }
  }

  private int revokeAll(String userId, String reason) {
    List<String> ids = sessionsById.values().stream().filter(s -> s.userId().equals(userId)).map(Session::id).toList();
    ids.forEach(this::revoke);
    audit.record(ActorType.SYSTEM, "auth", "SESSIONS_REVOKED", Map.of("userId", userId, "reason", reason), Map.of("count", ids.size()));
    return ids.size();
  }

  private void alert(String userId, String type, String message) {
    securityAlerts.computeIfAbsent(userId, k -> new ArrayList<>()).add(0, Map.of("type", type, "message", message, "at", clock.instant().toString(), "channel", "IN_APP (email/SMS simulated)"));
    audit.record(ActorType.SYSTEM, "auth", type, Map.of("userId", userId), Map.of());
  }

  private String token() { byte[] b = new byte[32]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
