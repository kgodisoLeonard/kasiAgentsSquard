package com.spaza.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/**
 * Append-only, tamper-evident audit log. Each entry stores the SHA-256 hash of the previous entry,
 * so editing or removing any row breaks every hash after it, which {@link #verify()} detects.
 */
@Service
public class AuditLog {
  public enum ActorType { HUMAN, AGENT, SYSTEM }
  public static final String GENESIS = "0".repeat(64);
  static final int MAX_FIELD = 7900;

  private final AuditEntryRepository repository;
  private final Clock clock;
  private final ObjectMapper json = new ObjectMapper()
      .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
      .findAndRegisterModules();
  private long lastSeq = -1;
  private String lastHash;

  public AuditLog(AuditEntryRepository repository, Clock clock) {
    this.repository = repository; this.clock = clock;
  }

  public synchronized AuditEntry record(ActorType actorType, String actorId, String action, Map<String,?> inputs, Map<String,?> outputs) {
    if (lastSeq < 0) {
      Optional<AuditEntry> last = repository.findTopByOrderBySeqDesc();
      lastSeq = last.map(AuditEntry::getSeq).orElse(0L);
      lastHash = last.map(AuditEntry::getHash).orElse(GENESIS);
    }
    long seq = lastSeq + 1;
    String ts = clock.instant().toString();
    String in = toJson(inputs), out = toJson(outputs);
    String actor = actorId == null || actorId.isBlank() ? "unknown" : actorId;
    String hash = hash(lastHash, seq, ts, actorType.name(), actor, action, in, out);
    AuditEntry saved = repository.save(new AuditEntry(seq, ts, actorType.name(), actor, action, in, out, lastHash, hash));
    lastSeq = seq; lastHash = hash;
    return saved;
  }

  /** Recomputes the whole chain. Returns valid=false and the first broken sequence number on tampering. */
  public synchronized Map<String,Object> verify() {
    String prev = GENESIS; long expected = 1; int count = 0;
    for (AuditEntry e : repository.findAllByOrderBySeqAsc()) {
      String problem = null;
      if (e.getSeq() != expected) problem = "Missing or reordered entry before seq " + e.getSeq();
      else if (!e.getPrevHash().equals(prev)) problem = "Previous-hash link broken";
      else if (!e.getHash().equals(hash(e.getPrevHash(), e.getSeq(), e.getTimestamp(), e.getActorType(), e.getActorId(), e.getAction(), e.getInputs(), e.getOutputs()))) problem = "Entry contents were modified";
      if (problem != null) return Map.of("valid", false, "brokenAtSeq", e.getSeq(), "reason", problem, "checked", count);
      prev = e.getHash(); expected++; count++;
    }
    return Map.of("valid", true, "checked", count, "headHash", prev);
  }

  public List<AuditEntry> recent() { return repository.findTop200ByOrderBySeqDesc(); }

  static String hash(String prev, long seq, String ts, String actorType, String actorId, String action, String in, String out) {
    return sha256(String.join("|", prev, Long.toString(seq), ts, actorType, actorId, action, in, out));
  }

  public static String sha256(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
  }

  private String toJson(Map<String,?> map) {
    String text;
    try { text = json.writeValueAsString(map == null ? Map.of() : new TreeMap<>(map)); }
    catch (Exception e) { text = "{\"unserialisable\":true}"; }
    // Column limit is 8000; truncate before hashing so the stored text and its hash always match.
    return text.length() > MAX_FIELD ? text.substring(0, MAX_FIELD) + "…[truncated]" : text;
  }
}
