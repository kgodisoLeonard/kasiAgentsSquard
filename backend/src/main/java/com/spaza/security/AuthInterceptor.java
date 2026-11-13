package com.spaza.security;

import com.spaza.security.AuthService.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.*;

/**
 * Deny-by-default access control for /api/**: every endpoint needs a valid session unless listed as public,
 * and @RequireRole is enforced on top. Also applies per-IP rate limits.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
  public static final String USER_ATTR = "spaza.authUser";
  private static final Set<String> PUBLIC = Set.of("/api/health", "/api/auth/login", "/api/auth/register", "/api/auth/mfa/verify", "/api/auth/refresh");
  private static final Set<String> AUTH_ENDPOINTS = Set.of("/api/auth/login", "/api/auth/register", "/api/auth/mfa/verify", "/api/auth/refresh");

  private final AuthService auth;
  private final RateLimiter apiLimit = new RateLimiter(300, 60);
  private final RateLimiter authLimit = new RateLimiter(30, 60);

  public AuthInterceptor(AuthService auth) { this.auth = auth; }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
    if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
    String path = req.getRequestURI();
    String ip = req.getRemoteAddr();
    if (!apiLimit.allow(ip)) return deny(res, 429, "Too many requests. Slow down and try again shortly.");
    if (AUTH_ENDPOINTS.contains(path) && !authLimit.allow(ip)) return deny(res, 429, "Too many sign-in requests from this network. Try again in a minute.");
    if (PUBLIC.contains(path)) return true;

    String header = req.getHeader("Authorization");
    AuthUser user = header != null && header.startsWith("Bearer ") ? auth.authenticate(header.substring(7).trim()) : null;
    if (user == null) return deny(res, 401, "SESSION_EXPIRED", "Please sign in to continue.");
    req.setAttribute(USER_ATTR, user);

    if (handler instanceof HandlerMethod hm) {
      RequireRole rule = Optional.ofNullable(hm.getMethodAnnotation(RequireRole.class)).orElse(hm.getBeanType().getAnnotation(RequireRole.class));
      if (rule != null && Arrays.stream(rule.value()).noneMatch(user.role()::equals)) return deny(res, 403, "Your account type is not allowed to do this.");
    }
    return true;
  }

  private static boolean deny(HttpServletResponse res, int status, String message) throws Exception { return deny(res, status, null, message); }

  private static boolean deny(HttpServletResponse res, int status, String code, String message) throws Exception {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"" + (code == null ? "" : ",\"code\":\"" + code + "\"") + "}");
    return false;
  }

  public static AuthUser current(HttpServletRequest req) {
    AuthUser u = (AuthUser) req.getAttribute(USER_ATTR);
    if (u == null) throw new AuthService.AuthException(401, "Please sign in to continue.");
    return u;
  }
}
