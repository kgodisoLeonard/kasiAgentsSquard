package com.spaza.security;

import com.spaza.audit.AuditLog;
import com.spaza.audit.AuditLog.ActorType;
import com.spaza.model.AppUser;
import com.spaza.persistence.StateStore;
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
 * on screen labelled "simulated SMS". Sessions are stored in the auth_sessions table (token hashes only), so they
 * survive a restart; retired refresh tokens, known devices and security alerts are kept in the state store.
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
  private record Grant(String userId, String action, Instant expires) {}

  private final AppUserRepository users;
  private final AuditLog audit;
  private final Clock clock;
  private final SupabaseAuth supabase;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final SecureRandom random = new SecureRandom();
  private final Map<String,int[]> failures = new HashMap<>();
  private final Map<String,Instant> lockedUntil = new HashMap<>();
  private final Map<String,Challenge> challenges = new HashMap<>();
  private final SessionRepository sessionRepo;
  private final StateStore store;
  private final Map<String,String> retiredRefresh = new HashMap<>();
  /** Read-through cache of sessions by access-token hash (the table stays the source of truth). */
  private final Map<String,SessionRecord> sessionCache = new HashMap<>();
  /** Every request needs the caller's account; re-read it from the database at most once a minute. */
  private final Map<String,com.spaza.persistence.Ttl<AppUser>> userCache = new HashMap<>();
  private final Map<String,Set<String>> knownDevices = new HashMap<>();
  private final Map<String,Grant> grants = new HashMap<>();
  private final Map<String,List<Map<String,Object>>> securityAlerts = new HashMap<>();

  public AuthService(AppUserRepository users, AuditLog audit, Clock clock, SupabaseAuth supabase, SessionRepository sessionRepo, StateStore store) {
    this.users = users; this.audit = audit; this.clock = clock; this.supabase = supabase; this.sessionRepo = sessionRepo; this.store = store;
    retiredRefresh.putAll(store.loadMap("retired-refresh", String.class));
    store.raw("known-devices").forEach((user, json) -> knownDevices.put(user, new HashSet<>(Arrays.asList(StateStore.read(json, String[].class)))));
    store.raw("security-alerts").forEach((user, json) -> {
      @SuppressWarnings("unchecked") List<Map<String,Object>> list = new ArrayList<>(StateStore.read(json, List.class));
      securityAlerts.put(user, list);
    });
  }

  // ------------------------------------------------------------- login + MFA

  /**
   * Sign in with business name + password and get a session straight away (no SMS one-time code at sign-in).
   * Lockout after repeated failures, new-device alerts and the audit trail still apply. One-time codes are still
   * used for sensitive actions (step-up), e.g. changing spending mandates.
   */
  public synchronized Map<String,Object> login(String businessName, String password, String deviceId, String ip) {
    AppUser user = checkPassword(businessName, password, ip);
    return finishLogin(user, deviceId, ip, "password");
  }

  /** Older two-step flow (password, then one-time code). Kept for API clients; the app signs in with {@link #login}. */
  public synchronized Map<String,Object> startLogin(String businessName, String password, String ip) {
    return issueChallenge(checkPassword(businessName, password, ip).getId(), "LOGIN");
  }

  /** Business name is matched case-insensitively. Throws on a wrong password or a locked account. */
  private AppUser checkPassword(String businessName, String password, String ip) {
    Instant now = clock.instant();
    String key = businessName.trim().toLowerCase(Locale.ROOT);
    Instant until = lockedUntil.get(key);
    if (until != null && now.isBefore(until)) {
      long mins = Math.max(1, Duration.between(now, until).toMinutes() + 1);
      throw new AuthException(429, "Too many failed sign-in attempts. Try again in " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
    }
    Optional<AppUser> user = users.findByBusinessNameIgnoreCase(businessName.trim()).stream()
        .filter(u -> passwordMatches(u, password)).findFirst();
    if (user.isEmpty()) {
      int count = ++failures.computeIfAbsent(key, k -> new int[1])[0];
      audit.record(ActorType.HUMAN, key, "LOGIN_FAILED", Map.of("ip", ip, "failures", count), Map.of());
      if (count >= MAX_FAILURES) {
        lockedUntil.put(key, now.plus(LOCKOUT)); failures.remove(key);
        audit.record(ActorType.SYSTEM, "auth", "ACCOUNT_LOCKED", Map.of("businessName", key), Map.of("minutes", LOCKOUT.toMinutes()));
        throw new AuthException(429, "Too many failed sign-in attempts. The account is locked for " + LOCKOUT.toMinutes() + " minutes.");
      }
      throw new AuthException(401, "Invalid business name or password");
    }
    failures.remove(key);
    return user.get();
  }

  public synchronized Map<String,Object> completeLogin(String challengeId, String code, String deviceId, String ip) {
    Challenge c = consumeChallenge(challengeId, code, "LOGIN");
    AppUser user = users.findById(c.userId()).orElseThrow(() -> new AuthException(401, "Account no longer exists"));
    return finishLogin(user, deviceId, ip, "OTP (simulated delivery)");
  }

  private Map<String,Object> finishLogin(AppUser user, String deviceId, String ip, String method) {
    String device = deviceId == null || deviceId.isBlank() ? "unknown-device" : deviceId.trim();
    String deviceHash = AuditLog.sha256(device);
    Set<String> devices = knownDevices.computeIfAbsent(user.getId(), k -> new HashSet<>());
    boolean added = devices.add(deviceHash);
    if (added) store.save("known-devices", user.getId(), devices.toArray(new String[0]));
    boolean newDevice = added && devices.size() > 1;
    if (newDevice) alert(user.getId(), "NEW_DEVICE_SIGN_IN", "A new device signed in to your account. If this was not you, sign out all devices.");
    Map<String,Object> tokens = openSession(user.getId(), deviceHash);
    audit.record(ActorType.HUMAN, user.getId(), "LOGIN_SUCCEEDED", Map.of("ip", ip, "method", method, "newDevice", newDevice), Map.of("sessionId", tokens.get("sessionId")));
    Map<String,Object> result = new LinkedHashMap<>(tokens); result.put("user", userView(user)); result.put("newDevice", newDevice);
    return result;
  }

  public synchronized Map<String,Object> refresh(String refreshToken) {
    String hash = AuditLog.sha256(String.valueOf(refreshToken));
    SessionRecord current = sessionRepo.findByRefreshHash(hash).orElse(null);
    if (current == null) {
      String stolen = retiredRefresh.get(hash);
      if (stolen != null) {
        // A rotated refresh token was used again: assume theft and end every session for that user.
        String userId = stolen;
        revokeAll(userId, "REFRESH_TOKEN_REUSE");
        alert(userId, "SESSION_REVOKED", "An old sign-in token was reused, so all your sessions were signed out for safety.");
      }
      throw new AuthException(401, "Session expired. Please sign in again.");
    }
    SessionRecord s = current;
    if (clock.instant().isAfter(s.refreshExpires())) { revoke(s.id()); throw new AuthException(401, "Session expired. Please sign in again."); }
    revoke(s.id());
    retiredRefresh.put(hash, s.userId());
    store.save("retired-refresh", hash, s.userId());
    Map<String,Object> tokens = openSession(s.userId(), s.deviceId());
    users.findById(s.userId()).ifPresent(u -> tokens.put("user", userView(u)));
    return tokens;
  }

  public synchronized AuthUser authenticate(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) return null;
    String accessHash = AuditLog.sha256(accessToken);
    SessionRecord s = sessionCache.get(accessHash);
    if (s == null) { s = sessionRepo.findByAccessHash(accessHash).orElse(null); if (s != null) sessionCache.put(accessHash, s); }
    if (s == null || clock.instant().isAfter(s.accessExpires())) return null;
    AppUser u = userCache.computeIfAbsent(s.userId(), id -> new com.spaza.persistence.Ttl<>(Duration.ofSeconds(60), () -> users.findById(id).orElse(null))).get();
    if (u == null) { revoke(s.id()); return null; }
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
    return sessionRepo.findByUserId(user.id()).stream().map(s -> Map.<String,Object>of(
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

  /** Supabase-linked accounts are checked by Supabase Auth; legacy and demo accounts by their bcrypt hash. */
  private boolean passwordMatches(AppUser u, String password) {
    if (u.getSupabaseId() != null && supabase.enabled()) return supabase.signIn(u.getEmail(), password);
    return u.getSupabaseId() == null && encoder.matches(password, u.getPasswordHash());
  }

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
    SessionRecord rec = sessionRepo.save(new SessionRecord(id, userId, deviceHash, now, now.plus(ACCESS_TTL), AuditLog.sha256(access), AuditLog.sha256(refresh), now.plus(REFRESH_TTL)));
    sessionCache.put(rec.accessHash(), rec);
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("accessToken", access); r.put("refreshToken", refresh); r.put("expiresIn", ACCESS_TTL.toSeconds()); r.put("sessionId", id);
    return r;
  }

  private void revoke(String sessionId) {
    if (sessionId == null) return;
    sessionCache.values().removeIf(x -> x.id().equals(sessionId));
    if (sessionRepo.existsById(sessionId)) sessionRepo.deleteById(sessionId);
  }

  private int revokeAll(String userId, String reason) {
    List<String> ids = sessionRepo.findByUserId(userId).stream().map(SessionRecord::id).toList();
    ids.forEach(this::revoke);
    audit.record(ActorType.SYSTEM, "auth", "SESSIONS_REVOKED", Map.of("userId", userId, "reason", reason), Map.of("count", ids.size()));
    return ids.size();
  }

  private void alert(String userId, String type, String message) {
    List<Map<String,Object>> list = securityAlerts.computeIfAbsent(userId, k -> new ArrayList<>());
    list.add(0, Map.of("type", type, "message", message, "at", clock.instant().toString(), "channel", "IN_APP (email/SMS simulated)"));
    store.save("security-alerts", userId, list);
    audit.record(ActorType.SYSTEM, "auth", type, Map.of("userId", userId), Map.of());
  }

  private String token() { byte[] b = new byte[32]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
}
