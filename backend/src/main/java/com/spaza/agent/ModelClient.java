package com.spaza.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A language model that supports function calling. Implemented by GeminiClient; replaced by a fake in tests. */
public interface ModelClient {
  /** True when an API key is configured. */
  boolean configured();
  String provider();
  String model();
  /** Sends one generateContent request (Gemini REST format) and returns the parsed JSON response. */
  JsonNode generate(ObjectNode request) throws Exception;
}
