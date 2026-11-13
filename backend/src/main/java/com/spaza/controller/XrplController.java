package com.spaza.controller;

import com.spaza.audit.AuditLog;
import com.spaza.audit.AuditLog.ActorType;
import com.spaza.finance.MandateService;
import com.spaza.finance.SimulatedXrplLedger;
import com.spaza.finance.SupplierRegistry;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Owner wallet. SIMULATED: no XRPL transaction is signed or submitted; balances and hashes come from SimulatedXrplLedger.
 * Payments can only go to a verified supplier's payout address and must pass the spending mandate first.
 */
@RestController
@RequestMapping("/api/xrpl")
@RequireRole("OWNER")
public class XrplController {
  public static final String STEP_UP_ACTION = "send payment";
  @Value("${xrpl.rlusd.issuer}") private String rlusdIssuer;

  private final SimulatedXrplLedger ledger;
  private final MandateService mandates;
  private final SupplierRegistry suppliers;
  private final AuthService auth;
  private final AuditLog audit;

  public XrplController(SimulatedXrplLedger ledger, MandateService mandates, SupplierRegistry suppliers, AuthService auth, AuditLog audit) {
    this.ledger = ledger; this.mandates = mandates; this.suppliers = suppliers; this.auth = auth; this.audit = audit;
  }

  @GetMapping("/status")
  public Map<String,Object> status(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    String wallet = SimulatedXrplLedger.walletOf(u.id());
    List<Map<String,Object>> txs = new ArrayList<>(ledger.transactionsFor(wallet).stream().map(SimulatedXrplLedger.Tx::view).toList());
    Collections.reverse(txs);
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("network", "testnet"); r.put("simulated", true);
    r.put("notice", "SIMULATED ledger: no transaction is signed or sent to the XRPL Testnet. Balances are demo values.");
    r.put("wsUrl", "wss://s.altnet.rippletest.net:51233 (not connected)"); r.put("rlusdIssuer", rlusdIssuer); r.put("connected", false);
    r.put("rlusdBalance", ledger.balance(wallet)); r.put("xrpBalance", 0);
    r.put("mandate", mandates.snapshot(u.id()).orElse(null));
    r.put("perTxLimit", mandates.snapshot(u.id()).map(m -> m.get("perOrderCap")).orElse(0));
    r.put("payees", suppliers.all().stream().filter(SupplierRegistry.SupplierInfo::canReceiveOrders).map(s -> Map.of("id", s.id(), "name", s.name(), "address", s.payoutAddress())).toList());
    r.put("transactions", txs);
    return r;
  }

  @PostMapping("/pay")
  public ResponseEntity<?> pay(@RequestBody Map<String,Object> body, HttpServletRequest req, @RequestHeader(value = "X-Step-Up", required = false) String stepUp) {
    AuthUser u = AuthInterceptor.current(req);
    BigDecimal amount;
    try { amount = new BigDecimal(String.valueOf(body.get("amount")).trim()); } catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount")); }
    String supplierId = String.valueOf(body.getOrDefault("supplierId", "")).trim();
    if (amount.signum() <= 0) return ResponseEntity.badRequest().body(Map.of("error", "Amount must be positive"));
    if (supplierId.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Choose a verified supplier to pay"));
    if (u.level() < 2) return ResponseEntity.status(403).body(Map.of("error", "Identity verification (Level 2) is required before making payments"));
    if (amount.compareTo(new BigDecimal("1000")) > 0) auth.requireStepUp(u, STEP_UP_ACTION, stepUp);

    MandateService.Decision d = mandates.authorize(new MandateService.PaymentRequest(u.id(), supplierId, amount, null, null, "HUMAN:" + u.id()));
    if (!d.allowed()) return ResponseEntity.unprocessableEntity().body(Map.of("error", "Payment blocked by mandate: " + d.reason(), "code", d.code()));
    String address = suppliers.find(supplierId).orElseThrow().payoutAddress();
    try {
      SimulatedXrplLedger.Tx tx = ledger.pay(SimulatedXrplLedger.walletOf(u.id()), address, amount, "direct:" + supplierId);
      audit.record(ActorType.HUMAN, u.id(), "DIRECT_PAYMENT", Map.of("supplierId", supplierId, "amount", amount.toPlainString()), tx.view());
      Map<String,Object> r = new LinkedHashMap<>(tx.view());
      r.put("success", true); r.put("engineResult", "SIMULATED_tesSUCCESS"); r.put("balance", ledger.balance(SimulatedXrplLedger.walletOf(u.id())));
      r.put("notice", "Simulated payment: nothing was sent to the XRPL Testnet.");
      return ResponseEntity.ok(r);
    } catch (RuntimeException e) {
      mandates.release(u.id(), amount);
      throw e;
    }
  }
}
