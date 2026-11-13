package com.spaza.audit;

import com.spaza.TestSupport.FakeAuditRepository;
import com.spaza.TestSupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
  private final FakeAuditRepository repo = new FakeAuditRepository();
  private final AuditLog log = new AuditLog(repo, new MutableClock(Instant.parse("2026-11-13T08:00:00Z")));

  private void write(int n) {
    for (int i = 0; i < n; i++) log.record(AuditLog.ActorType.AGENT, "finance-agent", "AGENT_ACTION_" + i, Map.of("i", i), Map.of("ok", true));
  }

  @Test void entriesAreHashChained() {
    write(3);
    assertEquals(AuditLog.GENESIS, repo.rows.get(1L).getPrevHash());
    assertEquals(repo.rows.get(1L).getHash(), repo.rows.get(2L).getPrevHash());
    assertEquals(true, log.verify().get("valid"));
    assertEquals(3, log.verify().get("checked"));
  }

  @Test void editingAnEntryIsDetected() {
    write(4);
    AuditEntry e = repo.rows.get(2L);
    repo.rows.put(2L, new AuditEntry(2, e.getTimestamp(), e.getActorType(), e.getActorId(), "MANDATE_CHANGED", "{\"perOrderCap\":1000000}", e.getOutputs(), e.getPrevHash(), e.getHash()));
    Map<String,Object> result = log.verify();
    assertEquals(false, result.get("valid"));
    assertEquals(2L, result.get("brokenAtSeq"));
  }

  @Test void deletingAnEntryIsDetected() {
    write(4);
    repo.rows.remove(3L);
    assertEquals(false, log.verify().get("valid"));
  }

  @Test void rewritingHashesStillBreaksTheChain() {
    write(3);
    AuditEntry e = repo.rows.get(1L);
    String forged = AuditLog.hash(e.getPrevHash(), 1, e.getTimestamp(), e.getActorType(), e.getActorId(), "FORGED", e.getInputs(), e.getOutputs());
    repo.rows.put(1L, new AuditEntry(1, e.getTimestamp(), e.getActorType(), e.getActorId(), "FORGED", e.getInputs(), e.getOutputs(), e.getPrevHash(), forged));
    Map<String,Object> result = log.verify();
    assertEquals(false, result.get("valid"));
    assertEquals(2L, result.get("brokenAtSeq"), "the next entry no longer links to the forged one");
  }

  @Test void appendContinuesTheChainAfterRestart() {
    write(2);
    AuditLog restarted = new AuditLog(repo, new MutableClock(Instant.parse("2026-11-13T09:00:00Z")));
    restarted.record(AuditLog.ActorType.HUMAN, "owner-demo", "LOGIN_SUCCEEDED", Map.of(), Map.of());
    assertEquals(3L, repo.rows.lastKey());
    assertEquals(true, restarted.verify().get("valid"));
  }
}
