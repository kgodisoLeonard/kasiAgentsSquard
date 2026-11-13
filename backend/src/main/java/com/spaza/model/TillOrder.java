package com.spaza.model;

import jakarta.persistence.*;
import java.time.Instant;

/** A customer's order at one shop's till. Lines (productId -> qty) are stored as JSON. */
@Entity
@Table(name = "till_orders", indexes = @Index(name = "ix_till_orders_owner_status", columnList = "ownerId,status"))
public class TillOrder {
  @Id private String id;
  @Column(nullable = false) private String ownerId;
  @Column(nullable = false) private String status;
  @Column(nullable = false) private Instant openedAt;
  @Column(nullable = false, columnDefinition = "TEXT") private String linesJson;

  protected TillOrder() {}
  public TillOrder(String id, String ownerId) { this.id = id; this.ownerId = ownerId; this.status = "OPEN"; this.openedAt = Instant.now(); this.linesJson = "{}"; }
  public String getId() { return id; } public String getOwnerId() { return ownerId; } public String getStatus() { return status; }
  public Instant getOpenedAt() { return openedAt; } public String getLinesJson() { return linesJson; }
  public void setStatus(String status) { this.status = status; } public void setLinesJson(String linesJson) { this.linesJson = linesJson; }
  /** Snapshot for the background writer. */
  public TillOrder copy() { TillOrder c = new TillOrder(id, ownerId); c.status = status; c.openedAt = openedAt; c.linesJson = linesJson; return c; }
}
