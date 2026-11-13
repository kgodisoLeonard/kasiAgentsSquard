package com.spaza.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Minimum password strength. The breached-password check is SIMULATED with a small local list of the most
 * common leaked passwords; a production build would query the HaveIBeenPwned k-anonymity range API.
 */
public final class PasswordPolicy {
  private static final Set<String> COMMON_BREACHED = Set.of("password", "password1", "password123", "12345678", "123456789", "1234567890",
      "qwerty123", "qwertyuiop", "iloveyou1", "abc12345", "admin123", "welcome1", "letmein1", "football1", "monkey123", "11111111", "passw0rd", "sunshine1");
  private PasswordPolicy() {}

  /** Returns null when acceptable, otherwise the reason it was rejected. */
  public static String problem(String password, String email) {
    if (password == null || password.length() < 8) return "Password must be at least 8 characters";
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) return "Password must be 72 characters or fewer";
    if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) return "Password must contain at least one letter and one number";
    String lower = password.toLowerCase(Locale.ROOT);
    if (COMMON_BREACHED.contains(lower)) return "This password appears in known data breaches. Choose a different one.";
    String local = email == null ? "" : email.split("@")[0].toLowerCase(Locale.ROOT);
    if (local.length() >= 4 && lower.contains(local)) return "Password must not contain your email name";
    return null;
  }
}
