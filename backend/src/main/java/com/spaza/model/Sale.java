package com.spaza.model;

import jakarta.persistence.*;
import java.time.Instant;

/** A completed till sale at one shop. Line items are stored as JSON. */
@Entity
@Table(name = "sales", indexes = @Index(name = "ix_sales_owner_time", columnList = "ownerId,createdAt"))
public class Sale {
  @Id private String id;
  @Column(nullable = false) private String ownerId;
  @Column(nullable = false) private Instant createdAt;
  @Column(nullable = false, columnDefinition = "TEXT") private String itemsJson;
  private double subtotal;
  private double profit;
  private String paymentMethod;
  private double changeDue;

  protected Sale() {}
  public Sale(String id, String ownerId, String itemsJson, double subtotal, double profit, String paymentMethod, double changeDue) {
    this.id = id; this.ownerId = ownerId; this.createdAt = Instant.now(); this.itemsJson = itemsJson; this.subtotal = subtotal; this.profit = profit;
    this.paymentMethod = paymentMethod; this.changeDue = changeDue;
  }
  public String getId() { return id; } public String getOwnerId() { return ownerId; } public Instant getCreatedAt() { return createdAt; }
  public String getItemsJson() { return itemsJson; } public double getSubtotal() { return subtotal; } public double getProfit() { return profit; }
  public String getPaymentMethod() { return paymentMethod; } public double getChangeDue() { return changeDue; }
}
