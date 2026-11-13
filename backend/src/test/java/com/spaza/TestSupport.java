package com.spaza;

import com.spaza.audit.AuditEntry;
import com.spaza.audit.AuditEntryRepository;
import com.spaza.audit.AuditLog;
import com.spaza.finance.*;
import com.spaza.model.AppUser;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** In-memory fakes so finance, agent and audit logic can be tested without Spring or a database. */
public final class TestSupport {
  public static final String OWNER_A = "owner-a", OWNER_B = "owner-b", OUTSIDER = "owner-c";
  public static final String SUPPLIER_ACCOUNT = "supplier-demo", OTHER_SUPPLIER_ACCOUNT = "supplier-ubuntu", UNVERIFIED_SUPPLIER = "supplier-new";

  public static class MutableClock extends Clock {
    private Instant now;
    public MutableClock(Instant start) { this.now = start; }
    public void advance(Duration d) { now = now.plus(d); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  public static class FakeAuditRepository implements AuditEntryRepository {
    public final TreeMap<Long,AuditEntry> rows = new TreeMap<>();
    @Override public AuditEntry save(AuditEntry e) { rows.put(e.getSeq(), e); return e; }
    @Override public Optional<AuditEntry> findTopByOrderBySeqDesc() { return rows.isEmpty() ? Optional.empty() : Optional.of(rows.lastEntry().getValue()); }
    @Override public List<AuditEntry> findAllByOrderBySeqAsc() { return new ArrayList<>(rows.values()); }
    @Override public List<AuditEntry> findTop200ByOrderBySeqDesc() { List<AuditEntry> l = new ArrayList<>(rows.descendingMap().values()); return l.subList(0, Math.min(200, l.size())); }
    public long count(String action) { return rows.values().stream().filter(e -> e.getAction().equals(action)).count(); }
    public List<AuditEntry> withAction(String action) { return rows.values().stream().filter(e -> e.getAction().equals(action)).toList(); }
  }

  public static class FakeMandateRepository implements SpendingMandateRepository {
    final Map<String,SpendingMandate> rows = new HashMap<>();
    @Override public SpendingMandate save(SpendingMandate m) { rows.put(m.getOwnerId(), m); return m; }
    @Override public Optional<SpendingMandate> findById(String id) { return Optional.ofNullable(rows.get(id)); }
  }

  /** A fully wired finance stack with two owners whose mandates allow SUP-001 (per-order R2000, daily R5000). */
  public static class Fixture {
    public final MutableClock clock = new MutableClock(Instant.parse("2026-11-13T08:00:00Z"));
    public final FakeAuditRepository auditRepo = new FakeAuditRepository();
    public final AuditLog audit = new AuditLog(auditRepo, clock);
    public final List<AppUser> users = new ArrayList<>(List.of(
        new AppUser(SUPPLIER_ACCOUNT, "Mokoena Meats", "supplier@mokoena.local", "x", "SUPPLIER", "Mokoena Meats (Pty) Ltd", true),
        new AppUser(OTHER_SUPPLIER_ACCOUNT, "Ubuntu", "ubuntu@coop.local", "x", "SUPPLIER", "Ubuntu Foods Co-op", true),
        new AppUser(UNVERIFIED_SUPPLIER, "New Supplier", "new@supplier.local", "x", "SUPPLIER", "Brand New Foods", false),
        new AppUser(OWNER_A, "Owner A", "a@spaza.local", "x", "OWNER", "A's Spaza", true),
        new AppUser(OWNER_B, "Owner B", "b@spaza.local", "x", "OWNER", "B's Spaza", true)));
    public final SupplierCatalog catalog = new SupplierCatalog();
    public final SupplierRegistry suppliers = new SupplierRegistry(() -> users);
    public final SimulatedXrplLedger ledger = new SimulatedXrplLedger(clock, suppliers::isVerifiedPayoutAddress);
    public final FakeMandateRepository mandateRepo = new FakeMandateRepository();
    public final MandateService mandates = new MandateService(mandateRepo, suppliers, audit, clock);
    public final GroupOrderService orders = new GroupOrderService(mandates, suppliers, ledger, audit, clock, Duration.ofHours(48));

    public Fixture() {
      setMandate(OWNER_A, "2000", "5000", List.of("SUP-001", UNVERIFIED_SUPPLIER));
      setMandate(OWNER_B, "2000", "5000", List.of("SUP-001"));
      mandates.updateAutonomy(human(OWNER_A), OWNER_A, new BigDecimal("3000"), List.of());
    }

    public Optional<AppUser> user(String id) { return users.stream().filter(u -> u.getId().equals(id)).findFirst(); }

    public void setMandate(String owner, String perOrder, String daily, List<String> allowed) {
      mandates.update(human(owner), owner, new BigDecimal(perOrder), new BigDecimal(daily), allowed, LocalDate.now(clock).plusDays(30), true);
    }

    /** Minced meat from SUP-001: R65/kg, minimum 50kg, R250 delivery, A and B take 25kg each (R1750 each). */
    public GroupOrder draftTwoMemberOrder() {
      return orders.draft(new GroupOrderService.DraftRequest("SUP-001", "Minced Meat", new BigDecimal("65"), 50, new BigDecimal("250"), Duration.ofMinutes(60),
          List.of(new GroupOrderService.MemberLine(OWNER_A, "Owner A", 25), new GroupOrderService.MemberLine(OWNER_B, "Owner B", 25)), "test"),
          AuditLog.ActorType.HUMAN, OWNER_A);
    }

    public BigDecimal balanceOf(String owner) { return ledger.balance(SimulatedXrplLedger.walletOf(owner)); }
    public BigDecimal supplierBalance() { return ledger.balance(suppliers.find("SUP-001").orElseThrow().payoutAddress()); }
  }

  public static MandateService.HumanActor human(String id) { return new MandateService.HumanActor(id, "OWNER"); }
  public static BigDecimal rands(String v) { return new BigDecimal(v); }
  private TestSupport() {}
}
