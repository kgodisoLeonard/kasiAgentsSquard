package com.spaza.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Google AI Studio (Gemini API) client using plain HTTPS. The API key is read from the server environment
 * (GEMINI_API_KEY) and sent only in the x-goog-api-key header; it is never logged or returned to the browser.
 */
public class GeminiClient implements ModelClient {
  private final String apiKey, model, baseUrl;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public GeminiClient(String apiKey, String model, String baseUrl) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.model = model == null || model.isBlank() ? "gemini-flash-latest" : model.trim();
    this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://generativelanguage.googleapis.com/v1beta" : baseUrl.trim().replaceAll("/+$", "");
  }

  @Override public boolean configured() { return !apiKey.isEmpty(); }
  @Override public String provider() { return "Google AI Studio (Gemini)"; }
  @Override public String model() { return model; }

  @Override public JsonNode generate(ObjectNode request) throws Exception {
    if (!configured()) throw new IllegalStateException("No GEMINI_API_KEY is set on the server");
    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent"))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("x-goog-api-key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() / 100 != 2) {
      String detail;
      try { detail = json.readTree(res.body()).path("error").path("message").asText(""); } catch (Exception e) { detail = ""; }
      throw new IllegalStateException("Gemini API error " + res.statusCode() + (detail.isBlank() ? "" : ": " + detail));
    }
    return json.readTree(res.body());
  }
}
