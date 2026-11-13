package com.spaza.controller;

import com.spaza.audit.AuditEntry;
import com.spaza.audit.AuditLog;
import com.spaza.finance.GroupOrder;
import com.spaza.finance.GroupOrderService;
import com.spaza.finance.SupplierRegistry;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Evidence packs built from platform records (the order, its escrow transactions and its audit entries),
 * fingerprinted with SHA-256. The agent summary only cites records; the decision is made by a human reviewer.
 */
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {
  private final GroupOrderService orders;
  private final SupplierRegistry suppliers;
  private final AuditLog audit;

  public DisputeController(GroupOrderService orders, SupplierRegistry suppliers, AuditLog audit) { this.orders = orders; this.suppliers = suppliers; this.audit = audit; }

  @GetMapping
  public Map<String,Object> list(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    return Map.of("success", true, "disputes", visible(u).stream().filter(o -> o.status() == GroupOrder.Status.DISPUTED || o.status() == GroupOrder.Status.REFUNDED)
        .map(o -> orders.view(o, u.id())).toList());
  }

  @GetMapping("/{orderId}/evidence-pack")
  public Map<String,Object> evidencePack(@PathVariable String orderId, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    GroupOrder o = orders.find(orderId).orElseThrow(() -> new NoSuchElementException("Order not found"));
    if (!visible(u).contains(o)) throw new SecurityException("Only parties to this order or a reviewer can view its evidence");
    Map<String,Object> view = orders.view(o, null);
    List<Map<String,Object>> records = audit.recent().stream().filter(e -> e.getInputs().contains("\"orderId\":\"" + orderId + "\""))
        .sorted(Comparator.comparingLong(AuditEntry::getSeq)).map(AuditController::view).toList();

    Map<String,Object> content = new LinkedHashMap<>();
    content.put("caseRef", orderId); content.put("status", o.status().name()); content.put("supplier", view.get("supplierName"));
    content.put("buyers", view.get("shares")); content.put("total", view.get("total")); content.put("disputeReason", view.get("disputeReason"));
    content.put("records", records);
    content.put("agentSummary", summary(o, records));
    String fingerprint = AuditLog.sha256(content.toString());

    Map<String,Object> r = new LinkedHashMap<>();
    r.put("success", true); r.put("documentTitle", "SPAZA EVIDENCE PACK - " + orderId); r.put("status", o.status().name());
    r.put("sha256Fingerprint", fingerprint); r.put("verificationCode", orderId + "-" + fingerprint.substring(0, 8).toUpperCase(Locale.ROOT));
    r.put("content", content);
    r.put("decisionProcess", "A human reviewer who is not a party decides (two reviewers for serious cases). Written reasons are required; 14-day appeal to a different reviewer (appeals workflow simulated).");
    return r;
  }

  /** Rule-based summary that cites audit sequence numbers. It recommends; it never decides. */
  private Map<String,Object> summary(GroupOrder o, List<Map<String,Object>> records) {
    List<Object> sources = new ArrayList<>();
    boolean dispatched = false, supplierSaysDelivered = false; int confirmations = 0, funded = 0;
    for (Map<String,Object> r : records) {
      String action = String.valueOf(r.get("action"));
      if (action.equals("SUPPLIER_DISPATCH")) { dispatched = true; sources.add(r.get("seq")); }
      if (action.equals("SUPPLIER_DELIVER")) { supplierSaysDelivered = true; sources.add(r.get("seq")); }
      if (action.equals("DELIVERY_CONFIRMED")) { confirmations++; sources.add(r.get("seq")); }
      if (action.equals("ESCROW_CREATED")) funded++;
    }
    String finding, recommendation, confidence;
    if (!dispatched) { finding = "No dispatch record exists for this order."; recommendation = "Refund buyers"; confidence = "HIGH"; }
    else if (supplierSaysDelivered && confirmations == 0) { finding = "Supplier marked delivered but no buyer confirmed receipt. Records conflict."; recommendation = "Escalate: request delivery proof (signed waybill, photos) from the supplier"; confidence = "LOW"; }
    else if (confirmations > 0 && confirmations < funded) { finding = confirmations + " of " + funded + " paying buyers confirmed delivery."; recommendation = "Escalate: partial confirmation needs human review"; confidence = "MEDIUM"; }
    else { finding = "Dispatch recorded; " + confirmations + " confirmation(s)."; recommendation = "Human review"; confidence = "MEDIUM"; }
    return Map.of("finding", finding, "recommendation", recommendation, "confidence", confidence, "sourceAuditSeq", sources,
        "note", "Generated from platform records only. Final decision by a human reviewer.");
  }

  private List<GroupOrder> visible(AuthUser u) {
    return switch (u.role()) {
      case "ADMIN" -> orders.all();
      case "OWNER" -> orders.forMember(u.id());
      case "SUPPLIER" -> suppliers.findByAccount(u.id()).map(s -> orders.forSupplier(s.id())).orElse(List.of());
      default -> List.of();
    };
  }
}
