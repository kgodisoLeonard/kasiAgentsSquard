package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Minimal HTTP client for feature tests: signs in like the browser does (business name + password + one-time code). */
final class Api {
  record Res(int status, JsonNode body) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;
  private String token;
  JsonNode lastLogin;

  Api(int port) { this.base = "http://localhost:" + port + "/api"; }

  Api login(String businessName, String password) {
    Res challenge = post("/auth/login", Map.of("businessName", businessName, "password", password, "deviceId", "feature-test"));
    if (challenge.status() != 200) throw new IllegalStateException("login failed for " + businessName + ": " + challenge.body());
    lastLogin = challenge.body();
    if (challenge.body().hasNonNull("accessToken")) { token = challenge.body().get("accessToken").asText(); return this; }   // no one-time code
    Res verified = post("/auth/mfa/verify", Map.of("challengeId", challenge.body().get("challengeId").asText(),
        "code", challenge.body().get("demoCode").asText(), "deviceId", "feature-test"));
    if (verified.status() != 200) throw new IllegalStateException("mfa failed: " + verified.body());
    token = verified.body().get("accessToken").asText();
    return this;
  }

  String token() { return token; }
  Api withToken(String t) { this.token = t; return this; }

  Res get(String path) { return send("GET", path, null); }
  Res post(String path, Object body) { return send("POST", path, body); }
  Res put(String path, Object body) { return send("PUT", path, body); }
  Res delete(String path) { return send("DELETE", path, null); }

  private Res send(String method, String path, Object body) {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
      if (token != null) b.header("Authorization", "Bearer " + token);
      b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
      HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      return new Res(r.statusCode(), r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body()));
    } catch (Exception e) { throw new RuntimeException(e); }
  }
}
