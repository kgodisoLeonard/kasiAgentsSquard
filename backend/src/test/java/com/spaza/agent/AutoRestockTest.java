package com.spaza.agent;

import com.spaza.TestSupport.Fixture;
import com.spaza.finance.GroupOrder;
import com.spaza.finance.MandateService.RestockRule;
import com.spaza.finance.SimulatedXrplLedger;
import com.spaza.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.spaza.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Autonomous restock: after a sale drops stock below the owner's rule, the agent orders exactly what the mandate says. */
class AutoRestockTest {
  private final Fixture f = new Fixture();
  private final Product bread = product("White Bread (700g)", 4);
  private final AgentGateway agent = new AgentGateway(f.mandates::snapshot, f.orders, f.suppliers, () -> List.of(bread), () -> true, f.audit, f.catalog, f::user);
  private static final BigDecimal START = SimulatedXrplLedger.STARTING_BALANCE;

  private static Product product(String name, int qty) { Product p = new Product(); p.setId("prod_" + qty); p.setName(name); p.setQty(qty); p.setLowStock(10); return p; }

  private void rule(String budget, int below, int qty, String supplier, String maxPrice) {
    f.mandates.updateAutonomy(human(OWNER_A), OWNER_A, new BigDecimal(budget), List.of(new RestockRule("White Bread (700g)", below, qty, supplier, new BigDecimal(maxPrice))));
  }

  @SuppressWarnings("unchecked")
  private Map<String,Object> run() {
    Map<String,Object> r = agent.execute("PLACE_RESTOCK_ORDER", Map.of("product", "White Bread (700g)"), OWNER_A);
    assertEquals(true, r.get("allowed"), String.valueOf(r));
    return (Map<String,Object>) r.get("result");
  }

  @BeforeEach void allowUbuntu() { f.setMandate(OWNER_A, "2000", "5000", List.of("SUP-001", "SUP-002")); }

  @Test void ordersMandatedQuantityFromMandatedSupplierWhenBelowLevel() {
    rule("3000", 5, 24, "SUP-002", "15");
    Map<String,Object> r = run();
    assertEquals("ORDERED", r.get("status"));
    GroupOrder o = f.orders.find(String.valueOf(r.get("orderId"))).orElseThrow();
    assertEquals("SUP-002", o.supplierId());
    assertEquals(24, o.totalQuantity());
    assertEquals(GroupOrder.Status.FUNDED, o.status(), "single-shop restock is paid into escrow straight away");
    assertEquals(0, START.subtract(new BigDecimal("312.00")).compareTo(f.balanceOf(OWNER_A)), "24 x R13 held in escrow");
    assertEquals(0, BigDecimal.ZERO.compareTo(f.ledger.balance(f.suppliers.find("SUP-002").orElseThrow().payoutAddress())), "supplier not paid before delivery");
    assertEquals(1, f.auditRepo.count("SHARE_PREAPPROVED_BY_MANDATE_RULE"));
  }

  @Test void doesNothingWhenStockIsAtOrAboveTheLevel() {
    rule("3000", 4, 24, "SUP-002", "15");          // stock 4 is not below 4
    assertEquals("STOCK_OK", run().get("code"));
    assertTrue(f.orders.all().isEmpty());
  }

  @Test void doesNotOrderTwiceWhileAnOrderIsOpen() {
    rule("3000", 5, 24, "SUP-002", "15");
    String first = String.valueOf(run().get("orderId"));
    Map<String,Object> second = run();
    assertEquals("ORDER_ALREADY_OPEN", second.get("code"));
    assertEquals(first, second.get("orderId"));
    assertEquals(1, f.orders.all().size());
  }

  @Test void noRuleMeansNoOrder() {
    assertEquals("NO_RULE", run().get("code"));
    assertTrue(f.orders.all().isEmpty());
  }

  @Test void priceAboveTheOwnersLimitIsEscalatedNotOrdered() {
    rule("3000", 5, 24, "SUP-002", "10");          // supplier charges R13
    Map<String,Object> r = run();
    assertEquals("PRICE_ABOVE_LIMIT", r.get("code"));
    assertTrue(f.orders.all().isEmpty());
    assertFalse(agent.notificationsFor(OWNER_A).isEmpty(), "owner is told");
  }

  @Test void monthlyAgentBudgetIsEnforced() {
    rule("300", 5, 24, "SUP-002", "15");           // R312 > R300 budget
    Map<String,Object> r = run();
    assertEquals("BLOCKED", r.get("status"));
    assertTrue(String.valueOf(r.get("reason")).contains("monthly budget"), String.valueOf(r));
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)), "no money moved");
  }

  @Test void perOrderCapStillApplies() {
    f.setMandate(OWNER_A, "300", "5000", List.of("SUP-001", "SUP-002"));
    rule("3000", 5, 24, "SUP-002", "15");
    assertEquals("BLOCKED", run().get("status"));
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)));
  }

  @Test void rulesMustUseApprovedSuppliersAndOnlyTheOwnerCanSetThem() {
    f.setMandate(OWNER_A, "2000", "5000", List.of("SUP-001"));
    assertThrows(IllegalArgumentException.class, () -> rule("3000", 5, 24, "SUP-002", "15"));
    assertThrows(SecurityException.class, () -> f.mandates.updateAutonomy(human(OWNER_B), OWNER_A, new BigDecimal("1"), List.of()));
  }

  @Test void refundGivesTheAgentBudgetBack() {
    rule("320", 5, 24, "SUP-002", "15");
    GroupOrder o = f.orders.find(String.valueOf(run().get("orderId"))).orElseThrow();
    f.clock.advance(java.time.Duration.ofHours(49));
    f.orders.processDeadlines();                    // delivery never confirmed -> refund
    assertEquals(GroupOrder.Status.REFUNDED, o.status());
    assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) f.mandates.snapshot(OWNER_A).orElseThrow().get("agentSpentThisMonth")));
  }
}
