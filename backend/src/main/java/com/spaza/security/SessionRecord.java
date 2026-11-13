package com.spaza.security;

import jakarta.persistence.*;
import java.time.Instant;

/** A signed-in session. Only SHA-256 hashes of the access and refresh tokens are stored, never the tokens. */
@Entity
@Table(name = "auth_sessions", indexes = {
    @Index(name = "ix_auth_sessions_access", columnList = "accessHash", unique = true),
    @Index(name = "ix_auth_sessions_refresh", columnList = "refreshHash", unique = true),
    @Index(name = "ix_auth_sessions_user", columnList = "userId")})
public class SessionRecord {
  @Id private String id;
  @Column(nullable = false) private String userId;
  @Column(nullable = false) private String deviceId;
  @Column(nullable = false) private Instant created;
  @Column(nullable = false) private Instant accessExpires;
  @Column(nullable = false, length = 64) private String accessHash;
  @Column(nullable = false, length = 64) private String refreshHash;
  @Column(nullable = false) private Instant refreshExpires;

  protected SessionRecord() {}
  public SessionRecord(String id, String userId, String deviceId, Instant created, Instant accessExpires, String accessHash, String refreshHash, Instant refreshExpires) {
    this.id = id; this.userId = userId; this.deviceId = deviceId; this.created = created; this.accessExpires = accessExpires;
    this.accessHash = accessHash; this.refreshHash = refreshHash; this.refreshExpires = refreshExpires;
  }
  public String id() { return id; } public String userId() { return userId; } public String deviceId() { return deviceId; } public Instant created() { return created; }
  public Instant accessExpires() { return accessExpires; } public String accessHash() { return accessHash; } public String refreshHash() { return refreshHash; }
  public Instant refreshExpires() { return refreshExpires; }
}
