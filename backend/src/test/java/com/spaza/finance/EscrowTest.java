package com.spaza.finance;

import com.spaza.TestSupport.Fixture;
import com.spaza.finance.GroupOrder.ShareStatus;
import com.spaza.finance.GroupOrder.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static com.spaza.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class EscrowTest {
  private final Fixture f = new Fixture();
  private static final BigDecimal START = SimulatedXrplLedger.STARTING_BALANCE;

  private void approveBoth(GroupOrder o) {
    f.orders.approveShare(o.id(), human(OWNER_A), true);
    f.orders.approveShare(o.id(), human(OWNER_B), true);
  }

  private void deliver(GroupOrder o) {
    for (String a : List.of("ACCEPT", "DISPATCH", "DELIVER")) f.orders.supplierAction(o.id(), SUPPLIER_ACCOUNT, a);
  }

  @Test void splitIsByQuantityAndAddsUpExactly() {
    List<GroupOrder.Share> shares = GroupOrderService.split(List.of(
        new GroupOrderService.MemberLine("a", "A", 10), new GroupOrderService.MemberLine("b", "B", 20), new GroupOrderService.MemberLine("c", "C", 30)),
        new BigDecimal("10.00"), new BigDecimal("100.00"));
    BigDecimal delivery = shares.stream().map(s -> s.delivery).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, new BigDecimal("100.00").compareTo(delivery), "delivery parts must add up to the fee");
    assertEquals(0, new BigDecimal("100.00").compareTo(shares.get(0).goods));
    assertEquals(0, new BigDecimal("16.66").compareTo(shares.get(0).delivery));
    assertEquals(0, new BigDecimal("50.01").compareTo(shares.get(2).delivery), "rounding remainder goes to the largest share");
  }

  @Test void everyMemberSeesTheSameOrderAndSplit() {
    GroupOrder o = f.draftTwoMemberOrder();
    var a = f.orders.view(o, OWNER_A); var b = f.orders.view(o, OWNER_B);
    assertEquals(a.get("shares"), b.get("shares"));
    assertEquals(a.get("unitPrice"), b.get("unitPrice"));
    assertEquals(0, new BigDecimal("3500.00").compareTo((BigDecimal) a.get("total")));
  }

  @Test void approvalHoldsMoneyInEscrowAndDoesNotPayTheSupplier() {
    GroupOrder o = f.draftTwoMemberOrder();
    var share = f.orders.approveShare(o.id(), human(OWNER_A), true);
    assertEquals(ShareStatus.FUNDED, share.status());
    assertEquals(0, START.subtract(new BigDecimal("1750.00")).compareTo(f.balanceOf(OWNER_A)));
    assertEquals(0, BigDecimal.ZERO.compareTo(f.supplierBalance()), "supplier is not paid before delivery");
    assertEquals("HELD", f.ledger.escrow(share.escrowId()).orElseThrow().status());
    assertEquals(Status.COLLECTING, o.status());
  }

  @Test void escrowIsReleasedDirectlyToSupplierWhenAllBuyersConfirm() {
    GroupOrder o = f.draftTwoMemberOrder();
    approveBoth(o);
    assertEquals(Status.FUNDED, o.status());
    deliver(o);
    f.orders.confirmDelivery(o.id(), human(OWNER_A));
    assertEquals(0, BigDecimal.ZERO.compareTo(f.supplierBalance()), "one confirmation is not enough");
    f.orders.confirmDelivery(o.id(), human(OWNER_B));
    assertEquals(Status.COMPLETED, o.status());
    assertEquals(0, new BigDecimal("3500.00").compareTo(f.supplierBalance()));
    o.shares().forEach(s -> assertEquals(ShareStatus.RELEASED, s.status()));
    // Every release transaction goes straight from escrow to the supplier's verified payout address.
    String supplierAddress = f.suppliers.find("SUP-001").orElseThrow().payoutAddress();
    f.ledger.transactionsFor(supplierAddress).stream().filter(t -> t.type().equals("EscrowFinish")).forEach(t -> assertEquals(supplierAddress, t.to()));
    assertEquals(2, f.auditRepo.count("ESCROW_RELEASED_TO_SUPPLIER"));
  }

  @Test void buyersAreRefundedWhenDeliveryIsNotConfirmedInTime() {
    GroupOrder o = f.draftTwoMemberOrder();
    approveBoth(o);
    deliver(o);
    f.orders.confirmDelivery(o.id(), human(OWNER_A));
    f.clock.advance(Duration.ofHours(49));
    assertEquals(List.of(o.id()), f.orders.processDeadlines());
    assertEquals(Status.REFUNDED, o.status());
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)));
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_B)));
    assertEquals(0, BigDecimal.ZERO.compareTo(f.supplierBalance()));
    assertEquals(2, f.auditRepo.count("ESCROW_REFUNDED"));
  }

  @Test void orderIsCancelledAndRefundedWhenSupplierMinimumIsNotMet() {
    GroupOrder o = f.draftTwoMemberOrder();
    f.orders.approveShare(o.id(), human(OWNER_A), true);
    f.orders.declineShare(o.id(), human(OWNER_B));
    assertEquals(Status.CANCELLED, o.status());
    assertEquals(ShareStatus.REFUNDED, o.share(OWNER_A).orElseThrow().status());
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)));
  }

  @Test void approvalDeadlineCancelsWhenMinimumNotMet() {
    GroupOrder o = f.draftTwoMemberOrder();
    f.orders.approveShare(o.id(), human(OWNER_A), true);
    f.clock.advance(Duration.ofMinutes(61));
    f.orders.processDeadlines();
    assertEquals(Status.CANCELLED, o.status());
    assertEquals(ShareStatus.EXPIRED, o.share(OWNER_B).orElseThrow().status());
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)));
  }

  @Test void mandateBlockedShareMovesNoMoney() {
    f.setMandate(OWNER_A, "1000", "5000", List.of("SUP-001"));
    GroupOrder o = f.draftTwoMemberOrder();
    var share = f.orders.approveShare(o.id(), human(OWNER_A), true);
    assertEquals(ShareStatus.BLOCKED, share.status());
    assertTrue(share.blockedReason().contains("per-order cap"));
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)));
    assertNull(share.escrowId());
  }

  @Test void unverifiedMemberCannotOrder() {
    GroupOrder o = f.draftTwoMemberOrder();
    var share = f.orders.approveShare(o.id(), human(OWNER_A), false);
    assertEquals(ShareStatus.BLOCKED, share.status());
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)));
  }

  @Test void membersApproveOnlyTheirOwnShare() {
    GroupOrder o = f.draftTwoMemberOrder();
    assertThrows(SecurityException.class, () -> f.orders.approveShare(o.id(), human(OUTSIDER), true));
    f.orders.approveShare(o.id(), human(OWNER_A), true);
    assertEquals(ShareStatus.PENDING, o.share(OWNER_B).orElseThrow().status(), "A's approval must not touch B's share");
  }

  @Test void onlyTheOrdersSupplierCanUpdateIt() {
    GroupOrder o = f.draftTwoMemberOrder();
    approveBoth(o);
    assertThrows(SecurityException.class, () -> f.orders.supplierAction(o.id(), OTHER_SUPPLIER_ACCOUNT, "ACCEPT"));
  }

  @Test void ordersToUnverifiedSuppliersAreRejected() {
    assertThrows(IllegalStateException.class, () -> f.orders.draft(new GroupOrderService.DraftRequest(UNVERIFIED_SUPPLIER, "Bread", new BigDecimal("10"), 1, BigDecimal.ZERO,
        Duration.ofMinutes(10), List.of(new GroupOrderService.MemberLine(OWNER_A, "A", 1)), "x"), com.spaza.audit.AuditLog.ActorType.HUMAN, OWNER_A));
  }

  @Test void ledgerRefusesToPayAnyAddressThatIsNotAVerifiedSupplier() {
    assertThrows(IllegalStateException.class, () -> f.ledger.createEscrow(SimulatedXrplLedger.walletOf(OWNER_A), "rAgentOrAttackerWallet1234567890", new BigDecimal("10"), "x"));
    assertThrows(IllegalStateException.class, () -> f.ledger.pay(SimulatedXrplLedger.walletOf(OWNER_A), SimulatedXrplLedger.walletOf(OWNER_B), new BigDecimal("10"), "x"));
  }

  @Test void disputeHoldsEscrowUntilAnIndependentHumanDecides() {
    GroupOrder o = f.draftTwoMemberOrder();
    approveBoth(o);
    f.orders.reportNonDelivery(o.id(), human(OWNER_A), "Nothing arrived");
    assertEquals(Status.DISPUTED, o.status());
    assertThrows(SecurityException.class, () -> f.orders.resolveDispute(o.id(), new MandateService.HumanActor(OWNER_B, "OWNER"), "REFUND", "x"));
    assertThrows(SecurityException.class, () -> f.orders.resolveDispute(o.id(), new MandateService.HumanActor(OWNER_A, "ADMIN"), "REFUND", "party"));
    f.orders.resolveDispute(o.id(), new MandateService.HumanActor("admin-1", "ADMIN"), "REFUND", "No dispatch record");
    assertEquals(Status.REFUNDED, o.status());
    assertEquals(0, START.compareTo(f.balanceOf(OWNER_A)));
  }
}
