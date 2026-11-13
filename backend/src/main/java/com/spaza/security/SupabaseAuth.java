package com.spaza.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaza.security.AuthService.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Supabase Auth (GoTrue) client. Passwords for accounts created through it live only in Supabase;
 * this app never stores them. Uses the project's publishable (anon) key, so it can only do what a
 * browser client could: sign up and sign in with email + password.
 */
@Service
public class SupabaseAuth {
  private final String url, key;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public SupabaseAuth(@Value("${spaza.supabase.url:}") String url, @Value("${spaza.supabase.key:}") String key) {
    this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
    this.key = key == null ? "" : key.trim();
  }

  public boolean enabled() { return !url.isEmpty() && !key.isEmpty(); }

  public record SignUp(String userId, boolean confirmationRequired) {}

  /** Creates the Supabase user. Profile fields go into user_metadata. */
  public SignUp signUp(String email, String password, Map<String,Object> metadata) {
    JsonNode body = call("/auth/v1/signup", Map.of("email", email, "password", password, "data", metadata));
    JsonNode user = body.has("user") ? body.path("user") : body;
    String id = user.path("id").asText("");
    if (id.isEmpty()) throw new AuthException(502, "Supabase did not return a user. Please try again.");
    return new SignUp(id, !body.hasNonNull("access_token"));
  }

  /** True when Supabase accepts the email + password. Throws for anything other than wrong credentials. */
  public boolean signIn(String email, String password) {
    try {
      call("/auth/v1/token?grant_type=password", Map.of("email", email, "password", password));
      return true;
    } catch (AuthException e) {
      if (e.status() == 400 && e.getMessage().toLowerCase().contains("invalid login credentials")) return false;
      throw e;
    }
  }

  private JsonNode call(String path, Map<String,Object> payload) {
    HttpResponse<String> res;
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(15))
          .header("Content-Type", "application/json").header("apikey", key)
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
      res = http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthException(503, "Could not reach the sign-in service. Please try again.");
    } catch (Exception e) {
      throw new AuthException(503, "Could not reach the sign-in service. Please try again.");
    }
    JsonNode body;
    try { body = json.readTree(res.body()); } catch (Exception e) { body = json.createObjectNode(); }
    if (res.statusCode() / 100 == 2) return body;
    String code = body.path("error_code").asText(body.path("error").asText(""));
    String msg = body.path("msg").asText(body.path("error_description").asText(body.path("message").asText("")));
    if (code.equals("email_not_confirmed") || msg.toLowerCase().contains("email not confirmed"))
      throw new AuthException(403, "Please confirm your email first: open the link Supabase sent to your inbox, then sign in.");
    if (res.statusCode() == 429)
      throw new AuthException(429, "Too many requests to the sign-in service. Wait a few minutes and try again.");
    int status = res.statusCode() >= 500 ? 502 : res.statusCode();
    throw new AuthException(status, msg.isBlank() ? "Sign-in service error (" + res.statusCode() + ")" : msg);
  }
}
