package com.spaza.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/**
 * One append-only, hash-chained audit record. Hibernate treats the entity as immutable and every
 * column is non-updatable, and the repository exposes no update or delete operations.
 */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditEntry {
  @Id @Column(updatable = false) private long seq;
  @Column(nullable = false, updatable = false) private String timestamp;
  @Column(nullable = false, updatable = false) private String actorType;
  @Column(nullable = false, updatable = false) private String actorId;
  @Column(nullable = false, updatable = false) private String action;
  @Column(nullable = false, updatable = false, length = 8000) private String inputs;
  @Column(nullable = false, updatable = false, length = 8000) private String outputs;
  @Column(nullable = false, updatable = false, length = 64) private String prevHash;
  @Column(nullable = false, updatable = false, length = 64) private String hash;

  protected AuditEntry() {}
  public AuditEntry(long seq, String timestamp, String actorType, String actorId, String action, String inputs, String outputs, String prevHash, String hash) {
    this.seq = seq; this.timestamp = timestamp; this.actorType = actorType; this.actorId = actorId; this.action = action;
    this.inputs = inputs; this.outputs = outputs; this.prevHash = prevHash; this.hash = hash;
  }
  public long getSeq() { return seq; }
  public String getTimestamp() { return timestamp; }
  public String getActorType() { return actorType; }
  public String getActorId() { return actorId; }
  public String getAction() { return action; }
  public String getInputs() { return inputs; }
  public String getOutputs() { return outputs; }
  public String getPrevHash() { return prevHash; }
  public String getHash() { return hash; }
}
