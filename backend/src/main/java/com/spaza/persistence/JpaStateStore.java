package com.spaza.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * StateStore backed by the app_state table (Supabase Postgres in production, H2 locally and in tests).
 * Saves are queued on the {@link WriteBehind} thread and coalesced: if a document changes several times before the
 * writer gets to it, only the latest version is written, in one batched upsert.
 */
@Component
public class JpaStateStore implements StateStore {
  private final AppStateRepository repo;
  private final JdbcTemplate jdbc;
  private final WriteBehind writer;
  private final String upsert;
  /** state key -> [kind, id, json] waiting to be written */
  private final Map<String,String[]> pending = new LinkedHashMap<>();

  public JpaStateStore(AppStateRepository repo, DataSource dataSource, WriteBehind writer) {
    this.repo = repo; this.jdbc = new JdbcTemplate(dataSource); this.writer = writer;
    boolean postgres;
    try (var c = dataSource.getConnection()) { postgres = c.getMetaData().getURL().startsWith("jdbc:postgresql"); }
    catch (Exception e) { postgres = false; }
    this.upsert = postgres
        ? "INSERT INTO app_state (state_key, kind, item_id, json, updated_at) VALUES (?,?,?,?,?) "
          + "ON CONFLICT (state_key) DO UPDATE SET json = EXCLUDED.json, updated_at = EXCLUDED.updated_at"
        : "MERGE INTO app_state (state_key, kind, item_id, json, updated_at) KEY (state_key) VALUES (?,?,?,?,?)";
  }

  @Override public void save(String kind, String id, Object value) {
    String json = StateStore.write(value);               // serialise now: later changes to the object are separate saves
    boolean schedule;
    synchronized (pending) { schedule = pending.isEmpty(); pending.put(AppState.key(kind, id), new String[]{kind, id, json}); }
    if (schedule) writer.submit("app_state", this::writePending);
  }

  private void writePending() {
    List<Object[]> rows = new ArrayList<>();
    synchronized (pending) {
      Timestamp now = Timestamp.from(Instant.now());
      pending.forEach((key, v) -> rows.add(new Object[]{key, v[0], v[1], v[2], now}));
      pending.clear();
    }
    if (!rows.isEmpty()) jdbc.batchUpdate(upsert, rows);
  }

  @Override public <T> List<T> loadAll(String kind, Class<T> type) {
    writer.flush();
    return repo.findByKind(kind).stream().map(r -> StateStore.read(r.getJson(), type)).toList();
  }

  @Override public Map<String,String> raw(String kind) {
    writer.flush();
    Map<String,String> out = new LinkedHashMap<>();
    repo.findByKind(kind).forEach(r -> out.put(r.getItemId(), r.getJson()));
    return out;
  }
}
