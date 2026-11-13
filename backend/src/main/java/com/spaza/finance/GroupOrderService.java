package com.spaza.finance;

import com.spaza.audit.AuditLog;
import com.spaza.audit.AuditLog.ActorType;
import com.spaza.finance.GroupOrder.Share;
import com.spaza.finance.GroupOrder.ShareStatus;
import com.spaza.finance.GroupOrder.Status;
import com.spaza.finance.MandateService.HumanActor;
import com.spaza.finance.MandateService.PaymentRequest;
import com.spaza.persistence.StateStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Group orders with per-member approval, a fair quantity-based split and escrow.
 *  - Each member approves only their own share. Approval triggers a mandate check, then an EscrowCreate
 *    from the member's wallet with the supplier's verified payout address as the fixed destination.
 *  - At the approval deadline, if the supplier minimum is not met the order is cancelled and every escrow refunded.
 *  - When every funded member confirms delivery, all escrows are released directly to the supplier.
 *  - If delivery is not confirmed by the delivery deadline, every escrow is refunded to its member.
 * Orders are kept in memory for speed and written to the state store on every change (kind "group-order"), then
 * reloaded at startup, so a restart never loses an order, its escrow references or its timeline.
 */
public class GroupOrderService {
  public record MemberLine(String ownerId, String ownerName, int quantity) {}
  public record DraftRequest(String supplierId, String product, BigDecimal unitPrice, int minQuantity, BigDecimal deliveryFee,
                             Duration approvalWindow, List<MemberLine> members, String reason) {}

  private final MandateService mandates;
  private final SupplierRegistry suppliers;
  private final SimulatedXrplLedger ledger;
  private final AuditLog audit;
  private final Clock clock;
  private final Duration deliveryWindow;
  private final Map<String,GroupOrder> orders = new LinkedHashMap<>();
  private final List<Consumer<GroupOrder>> completionListeners = new ArrayList<>();
  /** Every order event (drafted, share paid, funded, dispatched, confirmed, completed, ...) for the members' update feeds. */
  public record OrderEvent(GroupOrder order, String action, String actorType, String actorId, Map<String,?> inputs) {}
  private final List<Consumer<OrderEvent>> eventListeners = new ArrayList<>();
  private final StateStore store;
  private int sequence = 1000;
  static final String KIND = "group-order";

  public GroupOrderService(MandateService mandates, SupplierRegistry suppliers, SimulatedXrplLedger ledger, AuditLog audit, Clock clock, Duration deliveryWindow) {
    this(mandates, suppliers, ledger, audit, clock, deliveryWindow, StateStore.memory());
  }

  public GroupOrderService(MandateService mandates, SupplierRegistry suppliers, SimulatedXrplLedger ledger, AuditLog audit, Clock clock, Duration deliveryWindow,
                           StateStore store) {
    this.mandates = mandates; this.suppliers = suppliers; this.ledger = ledger; this.audit = audit; this.clock = clock; this.deliveryWindow = deliveryWindow;
    this.store = store;
    store.loadAll(KIND, GroupOrderState.class).stream().sorted(Comparator.comparingInt(GroupOrderState::number)).forEach(st -> {
      orders.put(st.id(), st.toOrder());
      sequence = Math.max(sequence, st.number());
    });
  }

  private void persist(GroupOrder o) { store.save(KIND, o.id, GroupOrderState.of(o)); }

  public void onCompleted(Consumer<GroupOrder> listener) { completionListeners.add(listener); }
  public void onEvent(Consumer<OrderEvent> listener) { eventListeners.add(listener); }

  /** Supplier price lists with stock. When set, drafts are checked against stock, redirected if needed, and reserve stock. */
  private SupplierCatalog catalog;
  public void useCatalog(SupplierCatalog catalog) { this.catalog = catalog; }

  // ---------------------------------------------------------------- drafting

  public synchronized GroupOrder draft(DraftRequest r, ActorType actorType, String actorId) {
    SupplierRegistry.SupplierInfo supplier = suppliers.find(r.supplierId()).orElseThrow(() -> new NoSuchElementException("Supplier not found"));
    if (!supplier.canReceiveOrders()) throw new IllegalStateException("Only verified suppliers can receive orders");
    if (r.product() == null || r.product().isBlank()) throw new IllegalArgumentException("Product is required");
    if (r.unitPrice() == null || r.unitPrice().signum() <= 0) throw new IllegalArgumentException("Unit price must be positive");
    if (r.members() == null || r.members().isEmpty()) throw new IllegalArgumentException("At least one member is required");
    if (r.members().stream().map(MemberLine::ownerId).distinct().count() != r.members().size()) throw new IllegalArgumentException("Each member can appear only once");
    if (r.members().stream().anyMatch(m -> m.quantity() < 1)) throw new IllegalArgumentException("Every member quantity must be at least 1");
    if (r.minQuantity() < 1) throw new IllegalArgumentException("Supplier minimum must be at least 1");
    BigDecimal fee = r.deliveryFee() == null ? BigDecimal.ZERO : SimulatedXrplLedger.money(r.deliveryFee());
    if (fee.signum() < 0) throw new IllegalArgumentException("Delivery fee cannot be negative");
    Duration window = r.approvalWindow() == null ? Duration.ofHours(24) : r.approvalWindow();
    if (window.isNegative() || window.isZero()) throw new IllegalArgumentException("Approval window must be positive");

    // Not enough stock at the chosen supplier: redirect to the cheapest other verified supplier that has enough.
    String reason = r.reason() == null ? "" : r.reason();
    int wanted = r.members().stream().mapToInt(MemberLine::quantity).sum();
    BigDecimal unitPrice = r.unitPrice();
    String redirectedFrom = null;
    if (catalog != null && catalog.price(supplier.id(), r.product()).isPresent() && !catalog.hasStock(supplier.id(), r.product(), wanted)) {
      int have = catalog.price(supplier.id(), r.product()).map(i -> i.stock() == null ? 0 : i.stock()).orElse(0);
      String fromName = supplier.name();
      SupplierCatalog.Item alt = catalog.alternative(r.product(), wanted, Set.of(supplier.id()),
              i -> suppliers.find(i.supplierId()).map(SupplierRegistry.SupplierInfo::canReceiveOrders).orElse(false))
          .orElseThrow(() -> new IllegalStateException(fromName + " only has " + have + " " + r.product() + " in stock and no other verified supplier has " + wanted + "."));
      redirectedFrom = supplier.name();
      reason = (reason.isBlank() ? "" : reason + " · ") + "Redirected from " + supplier.name() + " (only " + have + " in stock)";
      supplier = suppliers.find(alt.supplierId()).orElseThrow();
      unitPrice = alt.unitPrice();
    }
    List<Share> shares = split(r.members(), SimulatedXrplLedger.money(unitPrice), fee);
    BigDecimal total = shares.stream().map(Share::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.doubleValue() > supplier.orderLimit())
      throw new IllegalStateException("New supplier on trial: orders are limited to R" + (int) SupplierRegistry.TRIAL_ORDER_LIMIT + " until " + SupplierRegistry.TRIAL_DELIVERIES + " deliveries are completed");

    String id = "GO-" + (++sequence);
    Instant now = clock.instant();
    GroupOrder o = new GroupOrder(id, supplier.id(), supplier.name(), r.product().trim(), SimulatedXrplLedger.money(unitPrice), r.minQuantity(), fee,
        now.plus(window), now, actorType + ":" + actorId, reason, shares);
    if (catalog != null && catalog.price(supplier.id(), o.product).isPresent()) { catalog.reserve(supplier.id(), o.product, wanted); o.stockReserved = true; }
    orders.put(id, o);
    if (redirectedFrom != null) event(o, ActorType.SYSTEM, "stock-check", "GROUP_ORDER_REDIRECTED", Map.of("from", redirectedFrom, "product", o.product, "quantity", wanted),
        Map.of("to", supplier.name(), "unitPrice", o.unitPrice));
    event(o, actorType, actorId, "GROUP_ORDER_DRAFTED", Map.of("supplierId", supplier.id(), "product", o.product, "unitPrice", o.unitPrice,
        "minQuantity", o.minQuantity, "deliveryFee", fee, "members", r.members().size()), Map.of("orderId", id, "total", total, "approvalDeadline", o.approvalDeadline.toString()));
    return o;
  }

  /** Quantity-based split. Goods = qty x unit price. Delivery is split by quantity; the rounding remainder goes to the largest share so the parts add up exactly. */
  static List<Share> split(List<MemberLine> members, BigDecimal unitPrice, BigDecimal deliveryFee) {
    int totalQty = members.stream().mapToInt(MemberLine::quantity).sum();
    List<BigDecimal> deliveries = new ArrayList<>();
    BigDecimal allocated = BigDecimal.ZERO; int largest = 0;
    for (int i = 0; i < members.size(); i++) {
      BigDecimal d = deliveryFee.multiply(BigDecimal.valueOf(members.get(i).quantity())).divide(BigDecimal.valueOf(totalQty), 2, RoundingMode.DOWN);
      deliveries.add(d); allocated = allocated.add(d);
      if (members.get(i).quantity() > members.get(largest).quantity()) largest = i;
    }
    deliveries.set(largest, deliveries.get(largest).add(deliveryFee.subtract(allocated)));
    List<Share> shares = new ArrayList<>();
    for (int i = 0; i < members.size(); i++) {
      MemberLine m = members.get(i);
      shares.add(new Share(m.ownerId(), m.ownerName(), m.quantity(), unitPrice.multiply(BigDecimal.valueOf(m.quantity())).setScale(2, RoundingMode.HALF_UP), deliveries.get(i)));
    }
    return shares;
  }

  // ---------------------------------------------------------------- member approval and payment

  /** A member approves their own share. Payment then goes through the mandate check before any escrow is created. */
  public synchronized Share approveShare(String orderId, HumanActor member, boolean identityVerified) {
    GroupOrder o = get(orderId);
    Share s = memberShare(o, member.userId());
    if (o.status != Status.COLLECTING) throw new IllegalStateException("This order is no longer collecting approvals");
    if (clock.instant().isAfter(o.approvalDeadline)) { processDeadlines(); throw new IllegalStateException("The approval deadline has passed"); }
    if (s.status == ShareStatus.FUNDED) throw new IllegalStateException("You have already approved and funded your share");
    if (s.status != ShareStatus.PENDING && s.status != ShareStatus.BLOCKED) throw new IllegalStateException("Your share is " + s.status.name().toLowerCase(Locale.ROOT));
    if (!identityVerified) {
      s.status = ShareStatus.BLOCKED; s.blockedReason = "Identity verification (Level 2) is required before placing orders";
      event(o, ActorType.HUMAN, member.userId(), "SHARE_BLOCKED", Map.of("reason", "LEVEL_2_REQUIRED"), Map.of("status", s.status));
      return s;
    }
    event(o, ActorType.HUMAN, member.userId(), "SHARE_APPROVED", Map.of("amount", s.total, "quantity", s.quantity), Map.of());
    fund(o, s, "HUMAN:" + member.userId());
    settleIfReady(o);
    return s;
  }

  /** Used by the agent to (re)execute payment for a share the member already approved. Same mandate check applies. */
  public synchronized Share executeApprovedPayment(String orderId, String ownerId, String agentId) {
    GroupOrder o = get(orderId);
    Share s = memberShare(o, ownerId);
    if (o.status != Status.COLLECTING) throw new IllegalStateException("Order is not collecting payments");
    if (s.status != ShareStatus.BLOCKED || s.decidedAt == null || s.blockedReason == null || s.blockedReason.startsWith("Identity"))
      throw new IllegalStateException("Payment can only be retried for a share the member approved that was blocked by the mandate");
    fund(o, s, "AGENT:" + agentId);
    settleIfReady(o);
    return s;
  }

  /**
   * Automatic restock: a single-shop order placed by the agent under the owner's standing restock rule in their mandate.
   * The rule is the owner's pre-approval for exactly this product, quantity and supplier; payment still passes the full
   * mandate check (caps, allow-list, monthly agent budget) and is held in escrow until the owner confirms delivery.
   */
  public synchronized GroupOrder placeStandingOrder(String ownerId, String ownerName, boolean identityVerified, String supplierId, String product,
                                                    BigDecimal unitPrice, int quantity, String agentId, String rule) {
    GroupOrder o = draft(new DraftRequest(supplierId, product, unitPrice, quantity, BigDecimal.ZERO, Duration.ofHours(1),
        List.of(new MemberLine(ownerId, ownerName, quantity)), "Automatic restock: " + rule), ActorType.AGENT, agentId);
    Share s = o.shares.get(0);
    if (!identityVerified) {
      s.status = ShareStatus.BLOCKED; s.blockedReason = "Identity verification (Level 2) is required before placing orders"; s.decidedAt = clock.instant().toString();
      event(o, ActorType.AGENT, agentId, "SHARE_BLOCKED", Map.of("reason", "LEVEL_2_REQUIRED"), Map.of());
      return o;
    }
    event(o, ActorType.AGENT, agentId, "SHARE_PREAPPROVED_BY_MANDATE_RULE", Map.of("ownerId", ownerId, "rule", rule), Map.of("amount", s.total));
    fund(o, s, "AGENT:" + agentId);
    settleIfReady(o);
    return o;
  }

  /** An order for this product that the owner is part of and that is still in progress (not completed, cancelled or refunded). */
  public synchronized Optional<GroupOrder> openOrderForMember(String ownerId, String product) {
    EnumSet<Status> active = EnumSet.of(Status.COLLECTING, Status.FUNDED, Status.ACCEPTED, Status.IN_TRANSIT, Status.DELIVERED_PENDING_CONFIRMATION, Status.DISPUTED);
    return orders.values().stream().filter(o -> active.contains(o.status) && o.product.equalsIgnoreCase(product) && o.share(ownerId).isPresent()).findFirst();
  }

  public synchronized Share declineShare(String orderId, HumanActor member) {
    GroupOrder o = get(orderId);
    Share s = memberShare(o, member.userId());
    if (o.status != Status.COLLECTING || (s.status != ShareStatus.PENDING && s.status != ShareStatus.BLOCKED)) throw new IllegalStateException("Your share can no longer be declined");
    s.status = ShareStatus.DECLINED; s.decidedAt = clock.instant().toString();
    event(o, ActorType.HUMAN, member.userId(), "SHARE_DECLINED", Map.of(), Map.of());
    settleIfReady(o);
    return s;
  }

  private void fund(GroupOrder o, Share s, String initiatedBy) {
    s.decidedAt = clock.instant().toString();
    MandateService.Decision d = mandates.authorize(new PaymentRequest(s.ownerId, o.supplierId, s.total, o.id, o.approvalDeadline, initiatedBy));
    if (!d.allowed()) {
      s.status = ShareStatus.BLOCKED; s.blockedReason = d.reason();
      event(o, ActorType.SYSTEM, "mandate-service", "PAYMENT_BLOCKED", Map.of("ownerId", s.ownerId, "amount", s.total, "initiatedBy", initiatedBy), d.view());
      return;
    }
    String address = suppliers.find(o.supplierId).orElseThrow().payoutAddress();
    try {
      SimulatedXrplLedger.Tx tx = ledger.createEscrow(SimulatedXrplLedger.walletOf(s.ownerId), address, s.total, o.id + ":" + s.ownerId);
      s.status = ShareStatus.FUNDED; s.escrowId = tx.hash(); s.blockedReason = null; s.fundedBy = initiatedBy;
      event(o, ActorType.SYSTEM, "ledger", "ESCROW_CREATED", Map.of("ownerId", s.ownerId, "amount", s.total, "destination", address, "initiatedBy", initiatedBy), tx.view());
      flagRisk(o, s);
    } catch (RuntimeException e) {
      mandates.release(s.ownerId, s.total, initiatedBy.startsWith("AGENT:"));
      s.status = ShareStatus.BLOCKED; s.blockedReason = e.getMessage();
      event(o, ActorType.SYSTEM, "ledger", "PAYMENT_FAILED", Map.of("ownerId", s.ownerId, "amount", s.total), Map.of("error", String.valueOf(e.getMessage())));
    }
  }

  /** Lightweight fraud signals (flag only, never block): unusually large share, rapid repeated orders. */
  private void flagRisk(GroupOrder o, Share s) {
    List<Share> previous = orders.values().stream().filter(x -> x != o).flatMap(x -> x.shares.stream())
        .filter(x -> x.ownerId.equals(s.ownerId) && x.escrowId != null).toList();
    if (previous.size() >= 2) {
      BigDecimal avg = previous.stream().map(Share::total).reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(previous.size()), 2, RoundingMode.HALF_UP);
      if (s.total.compareTo(avg.multiply(BigDecimal.valueOf(3))) > 0) addFlag(o, s.ownerId, "UNUSUAL_ORDER_SIZE", "Share is more than 3x this member's average");
    }
    Instant tenMinutesAgo = clock.instant().minus(Duration.ofMinutes(10));
    long recent = previous.stream().filter(x -> x.decidedAt != null && Instant.parse(x.decidedAt).isAfter(tenMinutesAgo)).count();
    if (recent >= 3) addFlag(o, s.ownerId, "RAPID_REPEATED_ORDERS", "4 or more funded orders within 10 minutes");
  }

  private void addFlag(GroupOrder o, String ownerId, String code, String detail) {
    o.riskFlags.add(code + ": " + detail);
    event(o, ActorType.SYSTEM, "fraud-monitor", "FRAUD_FLAG", Map.of("ownerId", ownerId, "code", code), Map.of("detail", detail));
  }

  /** Locks the order once every member has answered; cancels it when the supplier minimum cannot be met. */
  private void settleIfReady(GroupOrder o) {
    if (o.status != Status.COLLECTING) return;
    boolean allAnswered = o.shares.stream().noneMatch(s -> s.status == ShareStatus.PENDING || s.status == ShareStatus.BLOCKED);
    if (allAnswered) decide(o);
  }

  private void decide(GroupOrder o) {
    if (o.fundedQuantity() >= o.minQuantity) {
      o.status = Status.FUNDED; o.deliveryDeadline = clock.instant().plus(deliveryWindow);
      releaseStock(o, o.totalQuantity() - o.fundedQuantity());   // shares that did not pay give their units back
      event(o, ActorType.SYSTEM, "escrow", "ORDER_FUNDED", Map.of("fundedQuantity", o.fundedQuantity(), "minQuantity", o.minQuantity),
          Map.of("heldInEscrow", o.heldInEscrow(), "deliveryDeadline", o.deliveryDeadline.toString()));
    } else {
      o.status = Status.CANCELLED;
      refundAll(o, "Supplier minimum of " + o.minQuantity + " not met (" + o.fundedQuantity() + " funded)");
    }
  }

  // ---------------------------------------------------------------- deadlines

  /** Runs on a schedule. Applies approval and delivery deadlines. Returns the ids of orders it changed. */
  public synchronized List<String> processDeadlines() {
    Instant now = clock.instant(); List<String> changed = new ArrayList<>();
    for (GroupOrder o : orders.values()) {
      if (o.status == Status.COLLECTING && now.isAfter(o.approvalDeadline)) {
        o.shares.stream().filter(s -> s.status == ShareStatus.PENDING || s.status == ShareStatus.BLOCKED).forEach(s -> s.status = ShareStatus.EXPIRED);
        event(o, ActorType.SYSTEM, "scheduler", "APPROVAL_DEADLINE_REACHED", Map.of(), Map.of("fundedQuantity", o.fundedQuantity(), "minQuantity", o.minQuantity));
        decide(o); changed.add(o.id);
      } else if (EnumSet.of(Status.FUNDED, Status.ACCEPTED, Status.IN_TRANSIT, Status.DELIVERED_PENDING_CONFIRMATION).contains(o.status)
          && o.deliveryDeadline != null && now.isAfter(o.deliveryDeadline)) {
        o.status = Status.REFUNDED;
        refundAll(o, "Delivery not confirmed by " + o.deliveryDeadline + (o.supplierMarkedDelivered ? " (supplier says delivered: dispute recommended)" : ""));
        changed.add(o.id);
      }
    }
    return changed;
  }

  private void releaseStock(GroupOrder o, int qty) {
    if (catalog != null && o.stockReserved && qty > 0) catalog.release(o.supplierId, o.product, qty);
  }

  private void refundAll(GroupOrder o, String reason) {
    // Cancelled while collecting: every reserved unit goes back. Refunded after funding: the funded units go back.
    releaseStock(o, o.status == Status.CANCELLED ? o.totalQuantity() : o.fundedQuantity());
    for (Share s : o.shares) {
      if (s.status != ShareStatus.FUNDED) continue;
      SimulatedXrplLedger.Tx tx = ledger.cancelEscrow(s.escrowId, "refund:" + o.id);
      mandates.release(s.ownerId, s.total, s.fundedBy != null && s.fundedBy.startsWith("AGENT:"));
      s.status = ShareStatus.REFUNDED;
      event(o, ActorType.SYSTEM, "escrow", "ESCROW_REFUNDED", Map.of("ownerId", s.ownerId, "reason", reason), tx.view());
    }
    event(o, ActorType.SYSTEM, "escrow", o.status == Status.CANCELLED ? "ORDER_CANCELLED" : "ORDER_REFUNDED", Map.of("reason", reason), Map.of("status", o.status));
  }

  // ---------------------------------------------------------------- supplier and delivery

  /**
   * The agent approves a member's PENDING share on the member's behalf. The caller (AgentGateway) checks the member's
   * Human Rules; payment then passes the member's full mandate check exactly as a human approval does.
   */
  public synchronized Share agentApproveShare(String orderId, String ownerId, boolean identityVerified, String agentId) {
    GroupOrder o = get(orderId);
    Share s = memberShare(o, ownerId);
    if (o.status != Status.COLLECTING) throw new IllegalStateException("This order is no longer collecting approvals");
    if (clock.instant().isAfter(o.approvalDeadline)) { processDeadlines(); throw new IllegalStateException("The approval deadline has passed"); }
    if (s.status != ShareStatus.PENDING) return s;   // already paid, declined or blocked: nothing to do
    if (!identityVerified) {
      s.status = ShareStatus.BLOCKED; s.blockedReason = "Identity verification (Level 2) is required before placing orders"; s.decidedAt = clock.instant().toString();
      event(o, ActorType.AGENT, agentId, "SHARE_BLOCKED", Map.of("ownerId", ownerId, "reason", "LEVEL_2_REQUIRED"), Map.of());
      return s;
    }
    event(o, ActorType.AGENT, agentId, "SHARE_APPROVED_BY_AGENT", Map.of("ownerId", ownerId, "amount", s.total, "quantity", s.quantity), Map.of());
    fund(o, s, "AGENT:" + agentId);
    if (s.status == ShareStatus.BLOCKED) event(o, ActorType.AGENT, agentId, "AGENT_APPROVAL_BLOCKED", Map.of("ownerId", ownerId), Map.of("reason", String.valueOf(s.blockedReason)));
    settleIfReady(o);
    return s;
  }

  /** PENDING shares of orders still collecting: (orderId, ownerId) pairs for the agent to look at. */
  public synchronized List<String[]> pendingShares() {
    List<String[]> out = new ArrayList<>();
    for (GroupOrder o : orders.values()) if (o.status == Status.COLLECTING && !clock.instant().isAfter(o.approvalDeadline))
      for (Share s : o.shares) if (s.status == ShareStatus.PENDING) out.add(new String[]{o.id, s.ownerId});
    return out;
  }

  /**
   * The supplier declines a paid order it has not accepted yet (e.g. no stock or no transport). Every member is refunded,
   * the order is cancelled, and the same order is redirected to the cheapest other verified supplier with enough stock
   * (each member approves again, or the agent does within their mandate and Human Rules). Returns the new order, if any.
   */
  public synchronized Optional<GroupOrder> supplierDecline(String orderId, String supplierUserId, String reason) {
    GroupOrder o = get(orderId);
    SupplierRegistry.SupplierInfo supplier = suppliers.find(o.supplierId).orElseThrow();
    if (!supplierUserId.equals(supplier.accountUserId())) throw new SecurityException("Only the order's supplier can update it");
    if (o.status != Status.FUNDED) throw new IllegalStateException("Only paid orders that are not yet accepted can be declined");
    String why = reason == null || reason.isBlank() ? "No reason given" : reason.trim();
    if (why.length() > 200) why = why.substring(0, 200);
    event(o, ActorType.HUMAN, supplierUserId, "SUPPLIER_DECLINED", Map.of("reason", why), Map.of());
    o.status = Status.CANCELLED;
    refundAll(o, "Supplier declined: " + why);
    if (catalog == null) return Optional.empty();
    List<MemberLine> members = o.shares.stream().filter(s -> s.status == ShareStatus.REFUNDED || s.status == ShareStatus.PENDING)
        .map(s -> new MemberLine(s.ownerId, s.ownerName, s.quantity)).toList();
    if (members.isEmpty()) return Optional.empty();
    int qty = members.stream().mapToInt(MemberLine::quantity).sum();
    Optional<SupplierCatalog.Item> alt = catalog.alternative(o.product, qty, Set.of(o.supplierId),
        i -> suppliers.find(i.supplierId()).map(SupplierRegistry.SupplierInfo::canReceiveOrders).orElse(false));
    if (alt.isEmpty()) return Optional.empty();
    GroupOrder next = draft(new DraftRequest(alt.get().supplierId(), o.product, alt.get().unitPrice(), Math.min(o.minQuantity, qty), o.deliveryFee,
        Duration.ofHours(24), members, "Redirected after " + supplier.name() + " declined: " + why), ActorType.SYSTEM, "supplier-redirect");
    event(o, ActorType.SYSTEM, "supplier-redirect", "ORDER_REDIRECTED", Map.of("to", next.supplierName), Map.of("newOrderId", next.id));
    return Optional.of(next);
  }

  public synchronized GroupOrder supplierAction(String orderId, String supplierUserId, String action) {
    GroupOrder o = get(orderId);
    SupplierRegistry.SupplierInfo supplier = suppliers.find(o.supplierId).orElseThrow();
    if (!supplierUserId.equals(supplier.accountUserId())) throw new SecurityException("Only the order's supplier can update it");
    String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
    Status next = switch (a) {
      case "ACCEPT" -> require(o, Status.FUNDED, Status.ACCEPTED);
      case "DISPATCH" -> require(o, Status.ACCEPTED, Status.IN_TRANSIT);
      case "DELIVER" -> require(o, Status.IN_TRANSIT, Status.DELIVERED_PENDING_CONFIRMATION);
      default -> throw new IllegalArgumentException("Unsupported supplier action");
    };
    o.status = next; if (next == Status.DELIVERED_PENDING_CONFIRMATION) o.supplierMarkedDelivered = true;
    event(o, ActorType.HUMAN, supplierUserId, "SUPPLIER_" + a, Map.of(), Map.of("status", next));
    return o;
  }

  public synchronized GroupOrder confirmDelivery(String orderId, HumanActor member) {
    GroupOrder o = get(orderId);
    Share s = memberShare(o, member.userId());
    if (!EnumSet.of(Status.IN_TRANSIT, Status.DELIVERED_PENDING_CONFIRMATION).contains(o.status)) throw new IllegalStateException("The order has not been dispatched yet");
    if (s.status != ShareStatus.FUNDED) throw new IllegalStateException("Only members who paid into escrow confirm delivery");
    if (s.deliveryConfirmed) throw new IllegalStateException("You have already confirmed delivery");
    s.deliveryConfirmed = true;
    event(o, ActorType.HUMAN, member.userId(), "DELIVERY_CONFIRMED", Map.of(), Map.of());
    boolean all = o.shares.stream().filter(x -> x.status == ShareStatus.FUNDED).allMatch(x -> x.deliveryConfirmed);
    if (all) release(o, "All paying members confirmed delivery");
    return o;
  }

  private void release(GroupOrder o, String reason) {
    for (Share s : o.shares) {
      if (s.status != ShareStatus.FUNDED) continue;
      SimulatedXrplLedger.Tx tx = ledger.finishEscrow(s.escrowId, "release:" + o.id);
      s.status = ShareStatus.RELEASED;
      event(o, ActorType.SYSTEM, "escrow", "ESCROW_RELEASED_TO_SUPPLIER", Map.of("ownerId", s.ownerId, "reason", reason), tx.view());
    }
    o.status = Status.COMPLETED;
    suppliers.recordCompletedDelivery(o.supplierId);
    event(o, ActorType.SYSTEM, "escrow", "ORDER_COMPLETED", Map.of("reason", reason), Map.of("paidToSupplier", o.total()));
    completionListeners.forEach(l -> l.accept(o));
  }

  // ---------------------------------------------------------------- disputes (human decision)

  public synchronized GroupOrder reportNonDelivery(String orderId, HumanActor member, String reason) {
    GroupOrder o = get(orderId);
    Share s = memberShare(o, member.userId());
    if (s.status != ShareStatus.FUNDED) throw new IllegalStateException("Only members with funds in escrow can open a delivery dispute");
    if (!EnumSet.of(Status.FUNDED, Status.ACCEPTED, Status.IN_TRANSIT, Status.DELIVERED_PENDING_CONFIRMATION).contains(o.status)) throw new IllegalStateException("This order cannot be disputed now");
    o.status = Status.DISPUTED; o.disputeReason = reason == null || reason.isBlank() ? "Non-delivery reported" : reason.trim();
    event(o, ActorType.HUMAN, member.userId(), "DISPUTE_OPENED", Map.of("reason", o.disputeReason), Map.of("escrow", "HELD pending human decision"));
    return o;
  }

  /** A human reviewer (not a party to the order) releases or refunds a disputed escrow. The agent cannot call this. */
  public synchronized GroupOrder resolveDispute(String orderId, HumanActor reviewer, String outcome, String writtenReason) {
    GroupOrder o = get(orderId);
    if (o.status != Status.DISPUTED) throw new IllegalStateException("Order is not in dispute");
    if (!"ADMIN".equals(reviewer.role())) throw new SecurityException("Only a platform reviewer can decide disputes");
    if (o.share(reviewer.userId()).isPresent()) throw new SecurityException("A party to the order cannot review its dispute");
    if (writtenReason == null || writtenReason.isBlank()) throw new IllegalArgumentException("Written reasons are required");
    event(o, ActorType.HUMAN, reviewer.userId(), "DISPUTE_DECIDED", Map.of("outcome", outcome, "reasons", writtenReason), Map.of());
    if ("RELEASE".equalsIgnoreCase(outcome)) release(o, "Dispute decided for supplier: " + writtenReason);
    else if ("REFUND".equalsIgnoreCase(outcome)) { o.status = Status.REFUNDED; refundAll(o, "Dispute decided for buyers: " + writtenReason); }
    else throw new IllegalArgumentException("Outcome must be RELEASE or REFUND");
    return o;
  }

  // ---------------------------------------------------------------- queries

  public synchronized List<GroupOrder> forMember(String ownerId) { return orders.values().stream().filter(o -> o.share(ownerId).isPresent()).toList(); }
  public synchronized List<GroupOrder> forSupplier(String supplierId) { return orders.values().stream().filter(o -> o.supplierId.equals(supplierId) && o.status != Status.COLLECTING && o.status != Status.CANCELLED).toList(); }
  public synchronized List<GroupOrder> all() { return List.copyOf(orders.values()); }
  public synchronized Optional<GroupOrder> find(String id) { return Optional.ofNullable(orders.get(id)); }
  public synchronized Optional<GroupOrder> openOrderFor(String supplierId, String product) {
    return orders.values().stream().filter(o -> o.status == Status.COLLECTING && o.supplierId.equals(supplierId) && o.product.equalsIgnoreCase(product)).findFirst();
  }

  /** Every member sees the same order, price and full split. */
  public synchronized Map<String,Object> view(GroupOrder o, String viewerId) {
    Map<String,Object> v = new LinkedHashMap<>();
    v.put("id", o.id); v.put("status", o.status); v.put("supplierId", o.supplierId); v.put("supplierName", o.supplierName);
    v.put("product", o.product); v.put("unitPrice", o.unitPrice); v.put("deliveryFee", o.deliveryFee); v.put("minQuantity", o.minQuantity);
    v.put("totalQuantity", o.totalQuantity()); v.put("fundedQuantity", o.fundedQuantity()); v.put("total", o.total());
    v.put("heldInEscrow", o.heldInEscrow()); v.put("approvalDeadline", o.approvalDeadline.toString());
    v.put("deliveryDeadline", o.deliveryDeadline == null ? null : o.deliveryDeadline.toString());
    v.put("draftedBy", o.draftedBy); v.put("reason", o.reason); v.put("createdAt", o.createdAt.toString());
    v.put("splitMethod", "BY_QUANTITY"); v.put("shares", o.shares.stream().map(Share::view).toList());
    v.put("yourShare", viewerId == null ? null : o.share(viewerId).map(Share::view).orElse(null));
    v.put("supplierMarkedDelivered", o.supplierMarkedDelivered); v.put("disputeReason", o.disputeReason);
    v.put("riskFlags", List.copyOf(o.riskFlags)); v.put("timeline", List.copyOf(o.timeline)); v.put("ledger", "SIMULATED XRPL Testnet escrow");
    return v;
  }

  private GroupOrder get(String id) { GroupOrder o = orders.get(id); if (o == null) throw new NoSuchElementException("Order not found"); return o; }
  private Share memberShare(GroupOrder o, String ownerId) { return o.share(ownerId).orElseThrow(() -> new SecurityException("You are not a member of this order")); }
  private Status require(GroupOrder o, Status from, Status to) {
    if (o.status != from) throw new IllegalStateException("Order must be " + from.name().replace('_', ' ').toLowerCase(Locale.ROOT) + " first (currently " + o.status.name().replace('_', ' ').toLowerCase(Locale.ROOT) + ")");
    return to;
  }
  private void event(GroupOrder o, ActorType type, String actor, String action, Map<String,?> in, Map<String,?> out) {
    Map<String,Object> inputs = new LinkedHashMap<>(in); inputs.put("orderId", o.id);
    var entry = audit.record(type, actor, action, inputs, out);
    o.timeline.add(Map.of("at", String.valueOf(entry.getTimestamp()), "actor", type + ":" + actor, "action", action, "auditSeq", entry.getSeq()));
    persist(o);   // every change to an order is followed by an event, so this writes each new state through
    for (Consumer<OrderEvent> l : eventListeners) {
      try { l.accept(new OrderEvent(o, action, type.name(), actor, inputs)); } catch (RuntimeException ignored) { /* a feed problem never blocks an order */ }
    }
  }
}
