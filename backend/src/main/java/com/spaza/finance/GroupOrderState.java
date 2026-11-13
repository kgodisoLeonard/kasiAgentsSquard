package com.spaza.finance;

import com.spaza.finance.GroupOrder.Share;
import com.spaza.finance.GroupOrder.ShareStatus;
import com.spaza.finance.GroupOrder.Status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** JSON snapshot of a GroupOrder (and its shares) for the state store. Written on every change, read back at startup. */
record GroupOrderState(String id, String supplierId, String supplierName, String product, String draftedBy, String reason,
                       BigDecimal unitPrice, BigDecimal deliveryFee, int minQuantity, String approvalDeadline, String createdAt,
                       List<ShareState> shares, List<Map<String,Object>> timeline, String status, String deliveryDeadline,
                       boolean supplierMarkedDelivered, String disputeReason, List<String> riskFlags) {

  record ShareState(String ownerId, String ownerName, int quantity, BigDecimal goods, BigDecimal delivery, String status,
                    String blockedReason, String escrowId, String decidedAt, String fundedBy, boolean deliveryConfirmed) {}

  static GroupOrderState of(GroupOrder o) {
    List<ShareState> shares = o.shares.stream().map(s -> new ShareState(s.ownerId, s.ownerName, s.quantity, s.goods, s.delivery, s.status.name(),
        s.blockedReason, s.escrowId, s.decidedAt, s.fundedBy, s.deliveryConfirmed)).toList();
    List<Map<String,Object>> timeline = o.timeline.stream().map(e -> {
      Map<String,Object> m = new LinkedHashMap<>(e); m.replaceAll((k, v) -> v instanceof Instant i ? i.toString() : v); return m;
    }).toList();
    return new GroupOrderState(o.id, o.supplierId, o.supplierName, o.product, o.draftedBy, o.reason, o.unitPrice, o.deliveryFee, o.minQuantity,
        o.approvalDeadline.toString(), o.createdAt.toString(), shares, timeline, o.status.name(),
        o.deliveryDeadline == null ? null : o.deliveryDeadline.toString(), o.supplierMarkedDelivered, o.disputeReason, List.copyOf(o.riskFlags));
  }

  GroupOrder toOrder() {
    List<Share> list = new ArrayList<>();
    for (ShareState s : shares) {
      Share x = new Share(s.ownerId(), s.ownerName(), s.quantity(), s.goods(), s.delivery());
      x.status = ShareStatus.valueOf(s.status()); x.blockedReason = s.blockedReason(); x.escrowId = s.escrowId();
      x.decidedAt = s.decidedAt(); x.fundedBy = s.fundedBy(); x.deliveryConfirmed = s.deliveryConfirmed();
      list.add(x);
    }
    GroupOrder o = new GroupOrder(id, supplierId, supplierName, product, unitPrice, minQuantity, deliveryFee, Instant.parse(approvalDeadline),
        Instant.parse(createdAt), draftedBy, reason, list);
    o.status = Status.valueOf(status);
    o.deliveryDeadline = deliveryDeadline == null ? null : Instant.parse(deliveryDeadline);
    o.supplierMarkedDelivered = supplierMarkedDelivered; o.disputeReason = disputeReason;
    if (timeline != null) o.timeline.addAll(timeline);
    if (riskFlags != null) o.riskFlags.addAll(riskFlags);
    return o;
  }

  int number() { try { return Integer.parseInt(id.substring(id.indexOf('-') + 1)); } catch (Exception e) { return 0; } }
}
