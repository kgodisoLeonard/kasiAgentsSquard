package com.spaza.finance;

import com.spaza.audit.AuditLog;
import com.spaza.audit.AuditLog.ActorType;
import com.spaza.persistence.StateStore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
  /** Owner-set automatic restock rule: when stock of the product falls below reorderBelow, order orderQuantity from supplierId. */
  public record RestockRule(String product, int reorderBelow, int orderQuantity, String supplierId, BigDecimal maxUnitPrice) {
    public Map<String,Object> view() { return Map.of("product", product, "reorderBelow", reorderBelow, "orderQuantity", orderQuantity, "supplierId", supplierId, "maxUnitPrice", maxUnitPrice); }
  }
  public record Decision(boolean allowed, String code, String reason) {
    public Map<String,Object> view() { return Map.of("allowed", allowed, "code", code, "reason", reason); }
  }

  private final SpendingMandateRepository repository;
  private final SupplierRegistry suppliers;
  private final AuditLog audit;
  private final Clock clock;
  private final Map<String,BigDecimal> spentByOwnerDay = new HashMap<>();
  private final Map<String,BigDecimal> agentSpentByOwnerMonth = new HashMap<>();
  private final ObjectMapper json = new ObjectMapper();

  private final StateStore store;

  public MandateService(SpendingMandateRepository repository, SupplierRegistry suppliers, AuditLog audit, Clock clock) {
    this(repository, suppliers, audit, clock, StateStore.memory());
  }

  /** Daily spend and the agent's monthly spend are written through to the state store, so caps and budgets hold across restarts. */
  public MandateService(SpendingMandateRepository repository, SupplierRegistry suppliers, AuditLog audit, Clock clock, StateStore store) {
    this.repository = repository; this.suppliers = suppliers; this.audit = audit; this.clock = clock; this.store = store;
    spentByOwnerDay.putAll(store.loadMap("spend-day", BigDecimal.class));
    agentSpentByOwnerMonth.putAll(store.loadMap("spend-agent-month", BigDecimal.class));
  }

  /** Mandates are read on every payment and restock check; keep them in memory once loaded. Saves stay synchronous. */
  private final Map<String,Optional<SpendingMandate>> mandateCache = new HashMap<>();

  private Optional<SpendingMandate> mandate(String ownerId) { return mandateCache.computeIfAbsent(ownerId, repository::findById); }

  private SpendingMandate saveMandate(SpendingMandate m) {
    SpendingMandate saved = repository.save(m);
    mandateCache.put(saved.getOwnerId(), Optional.of(saved));
    return saved;
  }

  private void saveSpend(String ownerId) {
    String day = dayKey(ownerId), month = monthKey(ownerId);
    if (spentByOwnerDay.containsKey(day)) store.save("spend-day", day, spentByOwnerDay.get(day));
    if (agentSpentByOwnerMonth.containsKey(month)) store.save("spend-agent-month", month, agentSpentByOwnerMonth.get(month));
  }

  public synchronized Decision authorize(PaymentRequest r) {
    Decision d = evaluate(r);
    if (d.allowed()) {
      spentByOwnerDay.merge(dayKey(r.ownerId()), r.amount(), BigDecimal::add);
      if (isAgent(r)) agentSpentByOwnerMonth.merge(monthKey(r.ownerId()), r.amount(), BigDecimal::add);
      saveSpend(r.ownerId());
    }
    Map<String,Object> in = new LinkedHashMap<>();
    in.put("ownerId", r.ownerId()); in.put("supplierId", r.supplierId()); in.put("amount", r.amount().toPlainString());
    in.put("orderId", String.valueOf(r.orderId())); in.put("initiatedBy", r.initiatedBy());
    audit.record(ActorType.SYSTEM, "mandate-service", d.allowed() ? "MANDATE_CHECK_PASSED" : "MANDATE_CHECK_BLOCKED", in, d.view());
    return d;
  }

  /** Gives back daily allowance when an escrow is refunded or a ledger submission fails. */
  public synchronized void release(String ownerId, BigDecimal amount) { release(ownerId, amount, false); }

  public synchronized void release(String ownerId, BigDecimal amount, boolean agentInitiated) {
    spentByOwnerDay.computeIfPresent(dayKey(ownerId), (k, v) -> v.subtract(amount).max(BigDecimal.ZERO));
    if (agentInitiated) agentSpentByOwnerMonth.computeIfPresent(monthKey(ownerId), (k, v) -> v.subtract(amount).max(BigDecimal.ZERO));
    saveSpend(ownerId);
  }

  private Decision evaluate(PaymentRequest r) {
    Optional<SpendingMandate> found = mandate(r.ownerId());
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
    if (isAgent(r)) {
      BigDecimal agentSpent = agentSpentByOwnerMonth.getOrDefault(monthKey(r.ownerId()), BigDecimal.ZERO);
      if (agentSpent.add(r.amount()).compareTo(m.getMonthlyAgentBudget()) > 0)
        return blocked("AGENT_BUDGET", "Would exceed the agent's monthly budget of R" + m.getMonthlyAgentBudget().toPlainString() + " (agent already spent R" + agentSpent.toPlainString() + " this month)");
    }
    return new Decision(true, "OK", "Within mandate");
  }

  /** Only the owning human may change a mandate. There is no agent code path to this method. */
  public synchronized SpendingMandate update(HumanActor actor, String ownerId, BigDecimal perOrderCap, BigDecimal dailyCap, List<String> allowedSuppliers, LocalDate validUntil, boolean enforceOrderDeadline) {
    if (actor == null || !"OWNER".equals(actor.role()) || !actor.userId().equals(ownerId)) throw new SecurityException("Only the owner of this mandate can change it");
    if (perOrderCap == null || perOrderCap.signum() <= 0 || dailyCap == null || dailyCap.signum() <= 0) throw new IllegalArgumentException("Caps must be positive amounts");
    if (perOrderCap.compareTo(dailyCap) > 0) throw new IllegalArgumentException("Per-order cap cannot exceed the daily cap");
    if (validUntil == null || validUntil.isBefore(LocalDate.now(clock))) throw new IllegalArgumentException("Valid-until date must be today or later");
    for (String id : allowedSuppliers) if (suppliers.find(id).isEmpty()) throw new IllegalArgumentException("Unknown supplier: " + id);
    SpendingMandate before = mandate(ownerId).orElse(null);
    Map<String,Object> previous = before == null ? Map.of("exists", false) : view(before);
    SpendingMandate m;
    if (before == null) m = new SpendingMandate(ownerId, perOrderCap, dailyCap, allowedSuppliers, validUntil, enforceOrderDeadline, actor.userId(), clock.instant());
    else { m = before; m.apply(perOrderCap, dailyCap, allowedSuppliers, validUntil, enforceOrderDeadline, actor.userId(), clock.instant()); }
    SpendingMandate saved = saveMandate(m);
    audit.record(ActorType.HUMAN, actor.userId(), "MANDATE_CHANGED", Map.of("ownerId", ownerId, "previous", previous), view(saved));
    return saved;
  }

  /** Only the owning human can set the agent's budget and restock rules. Rules must use approved suppliers. */
  public synchronized SpendingMandate updateAutonomy(HumanActor actor, String ownerId, BigDecimal monthlyBudget, List<RestockRule> rules) {
    if (actor == null || !"OWNER".equals(actor.role()) || !actor.userId().equals(ownerId)) throw new SecurityException("Only the owner of this mandate can change it");
    SpendingMandate m = mandate(ownerId).orElseThrow(() -> new IllegalStateException("Create your spending mandate before adding restock rules"));
    BigDecimal budget = monthlyBudget == null ? BigDecimal.ZERO : monthlyBudget;
    if (budget.signum() < 0) throw new IllegalArgumentException("Agent budget cannot be negative");
    Set<String> products = new HashSet<>();
    for (RestockRule r : rules) {
      if (r.product() == null || r.product().isBlank()) throw new IllegalArgumentException("Each restock rule needs a product");
      if (!products.add(r.product().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Only one restock rule per product: " + r.product());
      if (r.reorderBelow() < 1) throw new IllegalArgumentException("Reorder level must be at least 1 for " + r.product());
      if (r.orderQuantity() < 1) throw new IllegalArgumentException("Order quantity must be at least 1 for " + r.product());
      if (r.maxUnitPrice() == null || r.maxUnitPrice().signum() <= 0) throw new IllegalArgumentException("Maximum unit price must be positive for " + r.product());
      if (!m.getAllowedSuppliers().contains(r.supplierId())) throw new IllegalArgumentException("Supplier for " + r.product() + " must be on your approved supplier list");
    }
    Map<String,Object> previous = view(m);
    try { m.applyAutonomy(budget, json.writeValueAsString(rules), actor.userId(), clock.instant()); }
    catch (Exception e) { throw new IllegalArgumentException("Could not save restock rules"); }
    SpendingMandate saved = saveMandate(m);
    audit.record(ActorType.HUMAN, actor.userId(), "MANDATE_CHANGED", Map.of("ownerId", ownerId, "previous", previous, "part", "agent budget and restock rules"), view(saved));
    return saved;
  }

  public synchronized List<RestockRule> restockRules(String ownerId) {
    return mandate(ownerId).map(this::rules).orElse(List.of());
  }

  private List<RestockRule> rules(SpendingMandate m) {
    try { return json.readValue(m.getRestockRules(), new TypeReference<List<RestockRule>>() {}); }
    catch (Exception e) { return List.of(); }
  }

  /** Seeds demo restock rules and budget for a demo owner when none are set yet. */
  public synchronized void ensureDefaultRestock(String ownerId, BigDecimal budget, List<RestockRule> rules) {
    Optional<SpendingMandate> found = mandate(ownerId);
    if (found.isEmpty() || found.get().getMonthlyAgentBudget().signum() != 0 || !rules(found.get()).isEmpty()) return;
    SpendingMandate m = found.get();
    try { m.applyAutonomy(budget, json.writeValueAsString(rules), "system-seed", clock.instant()); } catch (Exception e) { return; }
    saveMandate(m);
    audit.record(ActorType.SYSTEM, "seed", "RESTOCK_RULES_SEEDED", Map.of("ownerId", ownerId), view(m));
  }

  /** Seeds a default mandate for demo accounts only when none exists. */
  public synchronized void ensureDefault(String ownerId, List<String> allowedSuppliers) {
    if (mandate(ownerId).isPresent()) return;
    SpendingMandate m = saveMandate(new SpendingMandate(ownerId, new BigDecimal("2000.00"), new BigDecimal("5000.00"), allowedSuppliers, LocalDate.now(clock).plusDays(90), true, "system-seed", clock.instant()));
    audit.record(ActorType.SYSTEM, "seed", "MANDATE_SEEDED", Map.of("ownerId", ownerId), view(m));
  }

  @Override public synchronized Optional<Map<String,Object>> snapshot(String ownerId) {
    return mandate(ownerId).map(this::view);
  }

  public synchronized BigDecimal spentToday(String ownerId) { return spentByOwnerDay.getOrDefault(dayKey(ownerId), BigDecimal.ZERO); }

  public Map<String,Object> view(SpendingMandate m) {
    Map<String,Object> v = new LinkedHashMap<>();
    v.put("ownerId", m.getOwnerId()); v.put("perOrderCap", m.getPerOrderCap()); v.put("dailyCap", m.getDailyCap());
    v.put("allowedSuppliers", List.copyOf(m.getAllowedSuppliers())); v.put("validUntil", m.getValidUntil().toString());
    v.put("enforceOrderDeadline", m.isEnforceOrderDeadline()); v.put("version", m.getVersion());
    v.put("updatedBy", m.getUpdatedBy()); v.put("updatedAt", m.getUpdatedAt().toString());
    v.put("spentToday", spentByOwnerDay.getOrDefault(dayKey(m.getOwnerId()), BigDecimal.ZERO));
    v.put("monthlyAgentBudget", m.getMonthlyAgentBudget());
    v.put("agentSpentThisMonth", agentSpentByOwnerMonth.getOrDefault(monthKey(m.getOwnerId()), BigDecimal.ZERO));
    v.put("restockRules", rules(m).stream().map(RestockRule::view).toList());
    return Collections.unmodifiableMap(v);
  }

  private String dayKey(String ownerId) { return ownerId + "@" + LocalDate.now(clock); }
  private String monthKey(String ownerId) { return ownerId + "@" + YearMonth.now(clock); }
  private static boolean isAgent(PaymentRequest r) { return r.initiatedBy() != null && r.initiatedBy().startsWith("AGENT:"); }
  private static Decision blocked(String code, String reason) { return new Decision(false, code, reason); }
}
