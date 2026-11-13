package com.spaza.agent;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Prompt-injection defence for any external text (supplier descriptions, chat, uploads).
 * The text is cleaned, length-limited and checked for instruction-like patterns. It is only ever
 * displayed or passed to a model as quoted data; the agent never derives actions from it.
 */
public final class UntrustedText {
  public record Inspection(String text, boolean suspicious, List<String> matched) {
    public Map<String,Object> view() { return Map.of("text", text, "suspicious", suspicious, "matched", matched, "treatedAs", "UNTRUSTED_DATA"); }
  }
  private static final int MAX_LENGTH = 1000;
  private static final Map<String,Pattern> PATTERNS = new LinkedHashMap<>();
  static {
    PATTERNS.put("override-instructions", Pattern.compile("(?i)\\b(ignore|disregard|forget)\\b.{0,30}\\b(previous|prior|above|all|your)\\b.{0,20}\\b(instructions?|rules?|prompt)"));
    PATTERNS.put("role-hijack", Pattern.compile("(?i)\\b(you are now|act as|new instructions|system prompt|developer mode)\\b"));
    PATTERNS.put("money-movement", Pattern.compile("(?i)\\b(send|transfer|pay|wire|move)\\b.{0,40}\\b(rlusd|xrp|funds?|money|r\\s?\\d+)"));
    PATTERNS.put("wallet-address", Pattern.compile("\\br[1-9A-HJ-NP-Za-km-z]{24,34}\\b"));
    PATTERNS.put("mandate-tampering", Pattern.compile("(?i)\\b(raise|increase|change|remove|disable|bypass)\\b.{0,30}\\b(mandate|limit|cap|allow-?list)"));
    PATTERNS.put("secret-exfiltration", Pattern.compile("(?i)\\b(seed|private key|password|secret|identity document|id number)\\b"));
  }
  private UntrustedText() {}

  public static Inspection inspect(String raw) {
    String text = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
    if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH) + "…";
    List<String> matched = new ArrayList<>();
    for (var e : PATTERNS.entrySet()) if (e.getValue().matcher(text).find()) matched.add(e.getKey());
    return new Inspection(text, !matched.isEmpty(), List.copyOf(matched));
  }

  /** Wraps text for a language model so it is clearly delimited as data, never as instructions. */
  public static String asModelData(String label, String raw) {
    String clean = inspect(raw).text().replace("</untrusted_data>", "");
    return "<untrusted_data source=\"" + label.replaceAll("[^a-zA-Z0-9_-]", "") + "\">\n" + clean + "\n</untrusted_data>";
  }
}
