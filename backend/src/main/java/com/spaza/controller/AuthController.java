package com.spaza.controller;

import com.spaza.audit.AuditLog;
import com.spaza.audit.AuditLog.ActorType;
import com.spaza.inventory.InventoryService;
import com.spaza.model.AppUser;
import com.spaza.repository.AppUserRepository;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.PasswordPolicy;
import com.spaza.security.SupabaseAuth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@RestController @RequestMapping("/api/auth")
public class AuthController {
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final int MAX_NAME = 100, MAX_EMAIL = 254, MAX_PASSWORD_BYTES = 72;

  private final AppUserRepository users;
  private final AuthService auth;
  private final AuditLog audit;
  private final SupabaseAuth supabase;
  private final InventoryService inventory;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public AuthController(AppUserRepository users, AuthService auth, AuditLog audit, SupabaseAuth supabase, InventoryService inventory) {
    this.users = users; this.auth = auth; this.audit = audit; this.supabase = supabase; this.inventory = inventory;
  }

  /** Business name + password. Returns the session tokens directly (no SMS code at sign-in). */
  @PostMapping("/login") public ResponseEntity<?> login(@RequestBody(required = false) Map<String,Object> body, HttpServletRequest req) {
    String business = text(body, "businessName"), password = text(body, "password");
    if (business.isEmpty() || password.isEmpty()) return error(400, "Business name and password are required");
    if (business.length() > MAX_NAME || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) return error(401, "Invalid business name or password");
    return ResponseEntity.ok(auth.login(business, password, text(body, "deviceId"), req.getRemoteAddr()));
  }

  /** Step 2: one-time code. Returns a short-lived access token and a rotating refresh token. */
  @PostMapping("/mfa/verify") public Map<String,Object> verify(@RequestBody(required = false) Map<String,Object> body, HttpServletRequest req) {
    return auth.completeLogin(text(body, "challengeId"), text(body, "code"), text(body, "deviceId"), req.getRemoteAddr());
  }

  @PostMapping("/refresh") public Map<String,Object> refresh(@RequestBody(required = false) Map<String,Object> body) {
    return auth.refresh(text(body, "refreshToken"));
  }

  @PostMapping("/register") public ResponseEntity<?> register(@RequestBody(required = false) Map<String,Object> body) {
    String name = text(body, "name"), email = text(body, "email").toLowerCase(Locale.ROOT), password = text(body, "password"), business = text(body, "businessName"), role = text(body, "role").toUpperCase(Locale.ROOT);
    if (name.isEmpty()) return error(400, "Your name and surname are required");
    if (business.isEmpty()) return error(400, "Business name is required");
    if (name.length() > MAX_NAME || business.length() > MAX_NAME) return error(400, "Name and business name must be " + MAX_NAME + " characters or fewer");
    if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) return error(400, "Enter a valid email address");
    String weak = PasswordPolicy.problem(password, email);
    if (weak != null) return error(400, weak);
    if (!(role.equals("OWNER") || role.equals("SUPPLIER"))) return error(400, "Choose an account type: spaza owner or supplier");
    if (users.existsByEmail(email)) return error(409, "An account with this email already exists");
    if (users.existsByBusinessNameIgnoreCase(business)) return error(409, "That business name is already registered. Sign in, or use a different name.");
    try {
      // New accounts start at Level 1 (unverified). Orders and payouts need Level 2 verification.
      String supabaseId = null, next = "Sign in to finish setting up your account.";
      boolean confirm = false;
      String hash = encoder.encode(password);
      if (supabase.enabled()) {
        // The password is stored by Supabase Auth only; the local hash is of a random value nobody knows.
        SupabaseAuth.SignUp signUp = supabase.signUp(email, password, Map.of("name", name, "business_name", business, "role", role));
        supabaseId = signUp.userId(); confirm = signUp.confirmationRequired();
        hash = encoder.encode(UUID.randomUUID().toString());
        if (confirm) next = "We sent a confirmation link to " + email + ". Open it, then sign in with your business name and password.";
      }
      AppUser fresh = new AppUser(UUID.randomUUID().toString(), name, email, hash, role, business, false);
      fresh.setSupabaseId(supabaseId);
      AppUser user = users.saveAndFlush(fresh);
      if ("OWNER".equals(role)) inventory.seedRandomStock(user.getId());   // DEMO: a new shop starts with a random shelf
      audit.record(ActorType.HUMAN, user.getId(), "ACCOUNT_REGISTERED", Map.of("role", role, "auth", supabaseId == null ? "local" : "supabase"), Map.of("level", 1));
      return ResponseEntity.status(201).body(Map.of("success", true, "user", AuthService.userView(user), "confirmEmail", confirm, "next", next));
    } catch (DataIntegrityViolationException e) {
      return error(409, "An account with this email already exists");
    }
  }

  @GetMapping("/me") public Map<String,Object> me(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    AppUser user = users.findById(u.id()).orElseThrow();
    return Map.of("success", true, "user", AuthService.userView(user), "alerts", auth.alerts(u.id()), "sessions", auth.sessions(u));
  }

  @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req) {
    auth.logout(AuthInterceptor.current(req));
    return Map.of("success", true);
  }

  @PostMapping("/logout-all") public Map<String,Object> logoutAll(HttpServletRequest req) {
    return Map.of("success", true, "sessionsEnded", auth.logoutEverywhere(AuthInterceptor.current(req)));
  }

  @PostMapping("/step-up") public Map<String,Object> stepUp(@RequestBody(required = false) Map<String,Object> body, HttpServletRequest req) {
    return auth.startStepUp(AuthInterceptor.current(req), text(body, "action"));
  }

  @PostMapping("/step-up/verify") public Map<String,Object> stepUpVerify(@RequestBody(required = false) Map<String,Object> body, HttpServletRequest req) {
    return auth.completeStepUp(AuthInterceptor.current(req), text(body, "challengeId"), text(body, "code"));
  }

  private static ResponseEntity<?> error(int status, String message) { return ResponseEntity.status(status).body(Map.of("error", message)); }
  private static String text(Map<String,Object> body, String key) {
    Object value = body == null ? null : body.get(key);
    return value == null ? "" : String.valueOf(value).trim();
  }
}
