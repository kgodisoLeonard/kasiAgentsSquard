package com.spaza.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** A bulk order shared by one or more spaza owners. Held in memory by GroupOrderService. */
public class GroupOrder {
  public enum Status { COLLECTING, FUNDED, ACCEPTED, IN_TRANSIT, DELIVERED_PENDING_CONFIRMATION, COMPLETED, CANCELLED, REFUNDED, DISPUTED }
  public enum ShareStatus { PENDING, FUNDED, BLOCKED, DECLINED, EXPIRED, REFUNDED, RELEASED }

  public static class Share {
    final String ownerId, ownerName; final int quantity; final BigDecimal goods, delivery, total;
    ShareStatus status = ShareStatus.PENDING; String blockedReason, escrowId, decidedAt; boolean deliveryConfirmed;
    Share(String ownerId, String ownerName, int quantity, BigDecimal goods, BigDecimal delivery) {
      this.ownerId = ownerId; this.ownerName = ownerName; this.quantity = quantity; this.goods = goods; this.delivery = delivery; this.total = goods.add(delivery);
    }
    public String ownerId() { return ownerId; } public int quantity() { return quantity; } public BigDecimal total() { return total; }
    public ShareStatus status() { return status; } public String escrowId() { return escrowId; } public String blockedReason() { return blockedReason; }
    public boolean deliveryConfirmed() { return deliveryConfirmed; }
    Map<String,Object> view() {
      Map<String,Object> v = new LinkedHashMap<>();
      v.put("ownerId", ownerId); v.put("ownerName", ownerName); v.put("quantity", quantity); v.put("goods", goods);
      v.put("delivery", delivery); v.put("total", total); v.put("status", status); v.put("blockedReason", blockedReason);
      v.put("escrowTx", escrowId); v.put("deliveryConfirmed", deliveryConfirmed); v.put("decidedAt", decidedAt);
      return v;
    }
  }

  final String id, supplierId, supplierName, product, draftedBy, reason;
  final BigDecimal unitPrice, deliveryFee; final int minQuantity; final Instant approvalDeadline, createdAt;
  final List<Share> shares; final List<Map<String,Object>> timeline = new ArrayList<>();
  Status status = Status.COLLECTING; Instant deliveryDeadline; boolean supplierMarkedDelivered; String disputeReason;
  final List<String> riskFlags = new ArrayList<>();

  GroupOrder(String id, String supplierId, String supplierName, String product, BigDecimal unitPrice, int minQuantity, BigDecimal deliveryFee,
             Instant approvalDeadline, Instant createdAt, String draftedBy, String reason, List<Share> shares) {
    this.id = id; this.supplierId = supplierId; this.supplierName = supplierName; this.product = product; this.unitPrice = unitPrice;
    this.minQuantity = minQuantity; this.deliveryFee = deliveryFee; this.approvalDeadline = approvalDeadline; this.createdAt = createdAt;
    this.draftedBy = draftedBy; this.reason = reason; this.shares = shares;
  }

  public String id() { return id; } public Status status() { return status; } public String supplierId() { return supplierId; }
  public String product() { return product; } public List<Share> shares() { return Collections.unmodifiableList(shares); }
  public Optional<Share> share(String ownerId) { return shares.stream().filter(s -> s.ownerId.equals(ownerId)).findFirst(); }
  public int totalQuantity() { return shares.stream().mapToInt(s -> s.quantity).sum(); }
  public int fundedQuantity() { return shares.stream().filter(s -> s.status == ShareStatus.FUNDED || s.status == ShareStatus.RELEASED).mapToInt(s -> s.quantity).sum(); }
  public BigDecimal total() { return shares.stream().map(s -> s.total).reduce(BigDecimal.ZERO, BigDecimal::add); }
  public BigDecimal heldInEscrow() { return shares.stream().filter(s -> s.status == ShareStatus.FUNDED).map(s -> s.total).reduce(BigDecimal.ZERO, BigDecimal::add); }
}
