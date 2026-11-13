package com.spaza.finance;

import com.spaza.audit.AuditLog;
import com.spaza.audit.AuditLog.ActorType;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * Enforces spending mandates outside the agent. Every payment, whether a human or the agent starts it,
 * must pass {@link #authorize} before any ledger transaction is created. Decisions are written to the audit log.
 */
public class MandateService implements MandateReader {
  /** A verified human, as established by the authentication layer. The agent never holds one of these. */
  public record HumanActor(String userId, String role) {}
  public record PaymentRequest(String ownerId, String supplierId, BigDecimal amount, String orderId, Instant orderDeadline, String initiatedBy) {}
  public record Decision(boolean allowed, String code, String reason) {
    public Map<String,Object> view() { return Map.of("allowed", allowed, "code", code, "reason", reason); }
  }

  private final SpendingMandateRepository repository;
  private final SupplierRegistry suppliers;
  private final AuditLog audit;
  private final Clock clock;
  private final Map<String,BigDecimal> spentByOwnerDay = new HashMap<>();

  public MandateService(SpendingMandateRepository repository, SupplierRegistry suppliers, AuditLog audit, Clock clock) {
    this.repository = repository; this.suppliers = suppliers; this.audit = audit; this.clock = clock;
  }

  public synchronized Decision authorize(PaymentRequest r) {
    Decision d = evaluate(r);
    if (d.allowed()) spentByOwnerDay.merge(dayKey(r.ownerId()), r.amount(), BigDecimal::add);
    Map<String,Object> in = new LinkedHashMap<>();
    in.put("ownerId", r.ownerId()); in.put("supplierId", r.supplierId()); in.put("amount", r.amount().toPlainString());
    in.put("orderId", String.valueOf(r.orderId())); in.put("initiatedBy", r.initiatedBy());
    audit.record(ActorType.SYSTEM, "mandate-service", d.allowed() ? "MANDATE_CHECK_PASSED" : "MANDATE_CHECK_BLOCKED", in, d.view());
    return d;
  }

  /** Gives back daily allowance when an escrow is refunded or a ledger submission fails. */
  public synchronized void release(String ownerId, BigDecimal amount) {
    spentByOwnerDay.computeIfPresent(dayKey(ownerId), (k, v) -> v.subtract(amount).max(BigDecimal.ZERO));
  }

  private Decision evaluate(PaymentRequest r) {
    Optional<SpendingMandate> found = repository.findById(r.ownerId());
    if (found.isEmpty()) return blocked("NO_MANDATE", "No spending mandate is set. The owner must create one before any payment.");
    SpendingMandate m = found.get();
    LocalDate today = LocalDate.now(clock);
    if (today.isAfter(m.getValidUntil())) return blocked("MANDATE_EXPIRED", "The spending mandate expired on " + m.getValidUntil());
    if (!m.getAllowedSuppliers().contains(r.supplierId())) return blocked("SUPPLIER_NOT_ALLOWED", "Supplier " + r.supplierId() + " is not on the owner's approved supplier allow-list");
    Optional<SupplierRegistry.SupplierInfo> supplier = suppliers.find(r.supplierId());
    if (supplier.isEmpty() || !supplier.get().verified()) return blocked("SUPPLIER_NOT_VERIFIED", "Supplier is not verified and cannot receive payments");
    if (supplier.get().sanctionsHit()) return blocked("SUPPLIER_SCREENING_HIT", "Supplier matched the (simulated) sanctions watch-list");
    if (r.amount() == null || r.amount().signum() <= 0) return blocked("INVALID_AMOUNT", "Amount must be positive");
    if (r.amount().compareTo(m.getPerOrderCap()) > 0) return blocked("PER_ORDER_CAP", "R" + r.amount().toPlainString() + " exceeds the per-order cap of R" + m.getPerOrderCap().toPlainString());
    BigDecimal spent = spentByOwnerDay.getOrDefault(dayKey(r.ownerId()), BigDecimal.ZERO);
    if (spent.add(r.amount()).compareTo(m.getDailyCap()) > 0) return blocked("DAILY_CAP", "Would exceed the daily cap of R" + m.getDailyCap().toPlainString() + " (already committed today: R" + spent.toPlainString() + ")");
    if (m.isEnforceOrderDeadline() && r.orderDeadline() != null && clock.instant().isAfter(r.orderDeadline())) return blocked("ORDER_DEADLINE_PASSED", "The order's approval deadline has passed");
    return new Decision(true, "OK", "Within mandate");
  }

  /** Only the owning human may change a mandate. There is no agent code path to this method. */
  public synchronized SpendingMandate update(HumanActor actor, String ownerId, BigDecimal perOrderCap, BigDecimal dailyCap, List<String> allowedSuppliers, LocalDate validUntil, boolean enforceOrderDeadline) {
    if (actor == null || !"OWNER".equals(actor.role()) || !actor.userId().equals(ownerId)) throw new SecurityException("Only the owner of this mandate can change it");
    if (perOrderCap == null || perOrderCap.signum() <= 0 || dailyCap == null || dailyCap.signum() <= 0) throw new IllegalArgumentException("Caps must be positive amounts");
    if (perOrderCap.compareTo(dailyCap) > 0) throw new IllegalArgumentException("Per-order cap cannot exceed the daily cap");
    if (validUntil == null || validUntil.isBefore(LocalDate.now(clock))) throw new IllegalArgumentException("Valid-until date must be today or later");
    for (String id : allowedSuppliers) if (suppliers.find(id).isEmpty()) throw new IllegalArgumentException("Unknown supplier: " + id);
    SpendingMandate before = repository.findById(ownerId).orElse(null);
    Map<String,Object> previous = before == null ? Map.of("exists", false) : view(before);
    SpendingMandate m;
    if (before == null) m = new SpendingMandate(ownerId, perOrderCap, dailyCap, allowedSuppliers, validUntil, enforceOrderDeadline, actor.userId(), clock.instant());
    else { m = before; m.apply(perOrderCap, dailyCap, allowedSuppliers, validUntil, enforceOrderDeadline, actor.userId(), clock.instant()); }
    SpendingMandate saved = repository.save(m);
    audit.record(ActorType.HUMAN, actor.userId(), "MANDATE_CHANGED", Map.of("ownerId", ownerId, "previous", previous), view(saved));
    return saved;
  }

  /** Seeds a default mandate for demo accounts only when none exists. */
  public synchronized void ensureDefault(String ownerId, List<String> allowedSuppliers) {
    if (repository.findById(ownerId).isPresent()) return;
    SpendingMandate m = repository.save(new SpendingMandate(ownerId, new BigDecimal("2000.00"), new BigDecimal("5000.00"), allowedSuppliers, LocalDate.now(clock).plusDays(90), true, "system-seed", clock.instant()));
    audit.record(ActorType.SYSTEM, "seed", "MANDATE_SEEDED", Map.of("ownerId", ownerId), view(m));
  }

  @Override public synchronized Optional<Map<String,Object>> snapshot(String ownerId) {
    return repository.findById(ownerId).map(this::view);
  }

  public synchronized BigDecimal spentToday(String ownerId) { return spentByOwnerDay.getOrDefault(dayKey(ownerId), BigDecimal.ZERO); }

  public Map<String,Object> view(SpendingMandate m) {
    Map<String,Object> v = new LinkedHashMap<>();
    v.put("ownerId", m.getOwnerId()); v.put("perOrderCap", m.getPerOrderCap()); v.put("dailyCap", m.getDailyCap());
    v.put("allowedSuppliers", List.copyOf(m.getAllowedSuppliers())); v.put("validUntil", m.getValidUntil().toString());
    v.put("enforceOrderDeadline", m.isEnforceOrderDeadline()); v.put("version", m.getVersion());
    v.put("updatedBy", m.getUpdatedBy()); v.put("updatedAt", m.getUpdatedAt().toString());
    v.put("spentToday", spentByOwnerDay.getOrDefault(dayKey(m.getOwnerId()), BigDecimal.ZERO));
    return Collections.unmodifiableMap(v);
  }

  private String dayKey(String ownerId) { return ownerId + "@" + LocalDate.now(clock); }
  private static Decision blocked(String code, String reason) { return new Decision(false, code, reason); }
}
