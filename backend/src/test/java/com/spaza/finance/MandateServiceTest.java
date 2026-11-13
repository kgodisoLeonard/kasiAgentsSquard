package com.spaza.finance;

import com.spaza.TestSupport.Fixture;
import com.spaza.finance.MandateService.Decision;
import com.spaza.finance.MandateService.HumanActor;
import com.spaza.finance.MandateService.PaymentRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.spaza.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class MandateServiceTest {
  private final Fixture f = new Fixture();

  private Decision pay(String owner, String supplier, String amount) {
    return f.mandates.authorize(new PaymentRequest(owner, supplier, rands(amount), "GO-1", null, "TEST"));
  }

  @Test void allowsPaymentWithinMandate() {
    assertTrue(pay(OWNER_A, "SUP-001", "1500").allowed());
  }

  @Test void blocksPaymentAbovePerOrderCap() {
    Decision d = pay(OWNER_A, "SUP-001", "2000.01");
    assertFalse(d.allowed());
    assertEquals("PER_ORDER_CAP", d.code());
  }

  @Test void blocksSupplierNotOnAllowList() {
    Decision d = pay(OWNER_B, "SUP-002", "100");
    assertFalse(d.allowed());
    assertEquals("SUPPLIER_NOT_ALLOWED", d.code());
  }

  @Test void blocksUnverifiedSupplierEvenWhenAllowListed() {
    Decision d = pay(OWNER_A, UNVERIFIED_SUPPLIER, "100");
    assertFalse(d.allowed());
    assertEquals("SUPPLIER_NOT_VERIFIED", d.code());
  }

  @Test void blocksWhenDailyCapWouldBeExceeded() {
    assertTrue(pay(OWNER_A, "SUP-001", "2000").allowed());
    assertTrue(pay(OWNER_A, "SUP-001", "2000").allowed());
    Decision third = pay(OWNER_A, "SUP-001", "1001");
    assertFalse(third.allowed());
    assertEquals("DAILY_CAP", third.code());
  }

  @Test void refundGivesDailyAllowanceBack() {
    assertTrue(pay(OWNER_A, "SUP-001", "2000").allowed());
    assertTrue(pay(OWNER_A, "SUP-001", "2000").allowed());
    f.mandates.release(OWNER_A, rands("2000"));
    assertTrue(pay(OWNER_A, "SUP-001", "1000").allowed());
  }

  @Test void blocksAfterMandateExpires() {
    f.clock.advance(Duration.ofDays(31));
    Decision d = pay(OWNER_A, "SUP-001", "100");
    assertFalse(d.allowed());
    assertEquals("MANDATE_EXPIRED", d.code());
  }

  @Test void blocksAfterOrderDeadline() {
    var deadline = f.clock.instant().plus(Duration.ofMinutes(5));
    f.clock.advance(Duration.ofMinutes(6));
    Decision d = f.mandates.authorize(new PaymentRequest(OWNER_A, "SUP-001", rands("100"), "GO-1", deadline, "TEST"));
    assertFalse(d.allowed());
    assertEquals("ORDER_DEADLINE_PASSED", d.code());
  }

  @Test void blocksOwnerWithoutMandate() {
    Decision d = pay(OUTSIDER, "SUP-001", "10");
    assertFalse(d.allowed());
    assertEquals("NO_MANDATE", d.code());
  }

  @Test void everyDecisionIsWrittenToTheAuditLog() {
    pay(OWNER_A, "SUP-001", "100");
    pay(OWNER_A, "SUP-001", "999999");
    assertEquals(1, f.auditRepo.count("MANDATE_CHECK_PASSED"));
    assertEquals(1, f.auditRepo.count("MANDATE_CHECK_BLOCKED"));
    assertTrue(f.auditRepo.withAction("MANDATE_CHECK_BLOCKED").get(0).getOutputs().contains("PER_ORDER_CAP"));
  }

  @Test void onlyTheOwningHumanCanChangeTheMandate() {
    List<String> allowed = List.of("SUP-001");
    LocalDate until = LocalDate.now(f.clock).plusDays(10);
    assertThrows(SecurityException.class, () -> f.mandates.update(human(OWNER_B), OWNER_A, rands("9999"), rands("9999"), allowed, until, true));
    assertThrows(SecurityException.class, () -> f.mandates.update(new HumanActor(OWNER_A, "SUPPLIER"), OWNER_A, rands("9999"), rands("9999"), allowed, until, true));
    assertThrows(SecurityException.class, () -> f.mandates.update(null, OWNER_A, rands("9999"), rands("9999"), allowed, until, true));
    assertEquals(0, new BigDecimal("2000.00").compareTo((BigDecimal) f.mandates.snapshot(OWNER_A).orElseThrow().get("perOrderCap")));
  }

  @Test void mandateChangesAreAudited() {
    long before = f.auditRepo.count("MANDATE_CHANGED");
    f.setMandate(OWNER_A, "500", "1000", List.of("SUP-001"));
    assertEquals(before + 1, f.auditRepo.count("MANDATE_CHANGED"));
    assertEquals("PER_ORDER_CAP", pay(OWNER_A, "SUP-001", "600").code());
  }

  @Test void rejectsInvalidMandates() {
    assertThrows(IllegalArgumentException.class, () -> f.setMandate(OWNER_A, "3000", "1000", List.of("SUP-001")));
    assertThrows(IllegalArgumentException.class, () -> f.setMandate(OWNER_A, "100", "1000", List.of("SUP-404")));
  }

  @Test void agentViewIsReadOnly() {
    Map<String,Object> snapshot = f.mandates.snapshot(OWNER_A).orElseThrow();
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("perOrderCap", 1_000_000));
    // The interface handed to the agent has exactly one method, and it only reads.
    Method[] methods = MandateReader.class.getDeclaredMethods();
    assertEquals(1, methods.length);
    assertEquals("snapshot", methods[0].getName());
  }
}
