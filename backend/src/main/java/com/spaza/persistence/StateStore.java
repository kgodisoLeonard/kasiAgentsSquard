package com.spaza.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.*;

/**
 * Durable key/value state for services that keep a working copy in memory (orders, escrow ledger, spend counters).
 * Each service writes through on every change and reloads everything at startup, so a restart loses nothing.
 * Values are stored as JSON documents grouped by kind. Production uses {@link JpaStateStore} (the app_state table);
 * unit tests use {@link #memory()}.
 */
public interface StateStore {
  ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  void save(String kind, String id, Object value);

  <T> List<T> loadAll(String kind, Class<T> type);

  default <T> Map<String,T> loadMap(String kind, Class<T> type) {
    Map<String,T> out = new LinkedHashMap<>();
    for (Map.Entry<String,String> e : raw(kind).entrySet()) out.put(e.getKey(), read(e.getValue(), type));
    return out;
  }

  /** id -> JSON for one kind. */
  Map<String,String> raw(String kind);

  static String write(Object value) {
    try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Could not serialise state", e); }
  }

  static <T> T read(String json, Class<T> type) {
    try { return JSON.readValue(json, type); } catch (Exception e) { throw new IllegalStateException("Could not read stored state", e); }
  }

  /** Non-durable store for unit tests. Round-trips through JSON so tests catch serialisation problems. */
  static StateStore memory() {
    return new StateStore() {
      private final Map<String,Map<String,String>> data = new LinkedHashMap<>();
      @Override public synchronized void save(String kind, String id, Object value) { data.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(id, write(value)); }
      @Override public synchronized <T> List<T> loadAll(String kind, Class<T> type) { return raw(kind).values().stream().map(v -> read(v, type)).toList(); }
      @Override public synchronized Map<String,String> raw(String kind) { return new LinkedHashMap<>(data.getOrDefault(kind, Map.of())); }
    };
  }
}
