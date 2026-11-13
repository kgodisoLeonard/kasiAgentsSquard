package com.spaza.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** One JSON document of service state, e.g. kind "group-order", id "GO-1001". */
@Entity
@Table(name = "app_state", indexes = @Index(name = "ix_app_state_kind", columnList = "kind"))
public class AppState {
  @Id @Column(length = 300) private String stateKey;
  @Column(nullable = false, length = 60) private String kind;
  @Column(nullable = false, length = 200) private String itemId;
  @Column(nullable = false, columnDefinition = "TEXT") private String json;
  @Column(nullable = false) private Instant updatedAt;

  protected AppState() {}
  public AppState(String kind, String itemId, String json) { this.stateKey = key(kind, itemId); this.kind = kind; this.itemId = itemId; this.json = json; this.updatedAt = Instant.now(); }
  public static String key(String kind, String itemId) { return kind + "/" + itemId; }

  public String getKind() { return kind; } public String getItemId() { return itemId; } public String getJson() { return json; }
  public void setJson(String json) { this.json = json; this.updatedAt = Instant.now(); }
}
