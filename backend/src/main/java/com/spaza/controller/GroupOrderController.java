package com.spaza.controller;

import com.spaza.audit.AuditLog.ActorType;
import com.spaza.finance.GroupOrder;
import com.spaza.finance.GroupOrderService;
import com.spaza.finance.GroupOrderService.DraftRequest;
import com.spaza.finance.GroupOrderService.MemberLine;
import com.spaza.finance.MandateService.HumanActor;
import com.spaza.finance.SupplierRegistry;
import com.spaza.repository.AppUserRepository;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/group-orders")
public class GroupOrderController {
  public static final BigDecimal LARGE_SHARE = new BigDecimal("1000.00");
  public static final String STEP_UP_LARGE = "approve large order";

  private final GroupOrderService orders;
  private final SupplierRegistry suppliers;
  private final AppUserRepository users;
  private final AuthService auth;

  public GroupOrderController(GroupOrderService orders, SupplierRegistry suppliers, AppUserRepository users, AuthService auth) {
    this.orders = orders; this.suppliers = suppliers; this.users = users; this.auth = auth;
  }

  @GetMapping
  public Map<String,Object> list(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    List<GroupOrder> visible = switch (u.role()) {
      case "OWNER" -> orders.forMember(u.id());
      case "SUPPLIER" -> suppliers.findByAccount(u.id()).map(s -> orders.forSupplier(s.id())).orElse(List.of());
      case "ADMIN" -> orders.all();
      default -> List.of();
    };
    List<Map<String,Object>> views = new ArrayList<>(visible.stream().map(o -> orders.view(o, u.id())).toList());
    Collections.reverse(views);
    return Map.of("success", true, "orders", views, "ledger", "SIMULATED XRPL Testnet escrow");
  }

  /** Verified owners who can be invited into a group order (the demo "community"). */
  @GetMapping("/members") @RequireRole("OWNER")
  public Map<String,Object> members() {
    return Map.of("success", true, "members", users.findAll().stream().filter(x -> "OWNER".equals(x.getRole()))
        .map(x -> Map.of("id", x.getId(), "name", x.getName(), "shop", x.getBusinessName(), "verified", x.isVerified())).toList());
  }

  /** A human owner drafts an order (the marketplace). The drafting owner must be a member; each member still approves their own share. */
  @PostMapping @RequireRole("OWNER")
  public Map<String,Object> create(@RequestBody Map<String,Object> body, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    int myQty = intOf(body.get("quantity"), "quantity");
    List<MemberLine> members = new ArrayList<>(List.of(new MemberLine(u.id(), u.name(), myQty)));
    if (body.get("invite") instanceof List<?> invites) for (Object o : invites) if (o instanceof Map<?,?> m) {
      String id = String.valueOf(m.get("ownerId"));
      var other = users.findById(id).filter(x -> "OWNER".equals(x.getRole())).orElseThrow(() -> new IllegalArgumentException("Unknown owner: " + id));
      if (!other.getId().equals(u.id())) members.add(new MemberLine(other.getId(), other.getName(), intOf(m.get("quantity"), "quantity")));
    }
    GroupOrder o = orders.draft(new DraftRequest(String.valueOf(body.get("supplierId")), String.valueOf(body.get("product")), dec(body.get("unitPrice")),
        intOf(body.getOrDefault("minQuantity", myQty), "minQuantity"), dec(body.getOrDefault("deliveryFee", 0)),
        Duration.ofMinutes(Math.max(1, intOf(body.getOrDefault("approvalMinutes", 1440), "approvalMinutes"))), members,
        members.size() > 1 ? "Group order created by " + u.name() : "Marketplace order"), ActorType.HUMAN, u.id());
    return Map.of("success", true, "order", orders.view(o, u.id()));
  }

  @PostMapping("/{id}/approve") @RequireRole("OWNER")
  public Map<String,Object> approve(@PathVariable String id, HttpServletRequest req, @RequestHeader(value = "X-Step-Up", required = false) String stepUp) {
    AuthUser u = AuthInterceptor.current(req);
    GroupOrder o = orders.find(id).orElseThrow(() -> new NoSuchElementException("Order not found"));
    var share = o.share(u.id()).orElseThrow(() -> new SecurityException("You are not a member of this order"));
    if (share.total().compareTo(LARGE_SHARE) > 0) auth.requireStepUp(u, STEP_UP_LARGE, stepUp);
    var s = orders.approveShare(id, new HumanActor(u.id(), u.role()), u.level() >= 2);
    String message = switch (s.status()) {
      case FUNDED -> "Your share is paid into escrow. It is released to the supplier only after delivery is confirmed.";
      case BLOCKED -> "Payment blocked: " + s.blockedReason();
      default -> "Share updated";
    };
    return Map.of("success", s.status() == GroupOrder.ShareStatus.FUNDED, "order", orders.view(o, u.id()), "message", message);
  }

  @PostMapping("/{id}/decline") @RequireRole("OWNER")
  public Map<String,Object> decline(@PathVariable String id, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    orders.declineShare(id, new HumanActor(u.id(), u.role()));
    return Map.of("success", true, "order", orders.view(orders.find(id).orElseThrow(), u.id()));
  }

  @PostMapping("/{id}/confirm-delivery") @RequireRole("OWNER")
  public Map<String,Object> confirm(@PathVariable String id, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    GroupOrder o = orders.confirmDelivery(id, new HumanActor(u.id(), u.role()));
    return Map.of("success", true, "order", orders.view(o, u.id()), "message", o.status() == GroupOrder.Status.COMPLETED
        ? "All buyers confirmed. Escrow released directly to the supplier." : "Delivery confirmed. Waiting for the other buyers.");
  }

  @PostMapping("/{id}/report-non-delivery") @RequireRole("OWNER")
  public Map<String,Object> dispute(@PathVariable String id, @RequestBody(required = false) Map<String,Object> body, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    GroupOrder o = orders.reportNonDelivery(id, new HumanActor(u.id(), u.role()), body == null ? "" : String.valueOf(body.getOrDefault("reason", "")));
    return Map.of("success", true, "order", orders.view(o, u.id()), "message", "Dispute opened. Escrow stays held until a human reviewer decides.");
  }

  @PostMapping("/{id}/supplier-action") @RequireRole("SUPPLIER")
  public Map<String,Object> supplierAction(@PathVariable String id, @RequestBody Map<String,Object> body, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    if ("DECLINE".equalsIgnoreCase(String.valueOf(body.get("action")))) {
      Optional<GroupOrder> next = orders.supplierDecline(id, u.id(), String.valueOf(body.getOrDefault("reason", "")));
      Map<String,Object> r = new LinkedHashMap<>();
      r.put("success", true); r.put("order", orders.view(orders.find(id).orElseThrow(), u.id()));
      r.put("redirectedTo", next.map(GroupOrder::id).orElse(null));
      r.put("message", next.map(n -> "Declined. Buyers were refunded and the order moved to " + orders.view(n, null).get("supplierName") + " (" + n.id() + ").")
          .orElse("Declined. Buyers were refunded; no other verified supplier has enough stock."));
      return r;
    }
    GroupOrder o = orders.supplierAction(id, u.id(), String.valueOf(body.get("action")));
    return Map.of("success", true, "order", orders.view(o, u.id()));
  }

  @PostMapping("/{id}/resolve") @RequireRole("ADMIN")
  public Map<String,Object> resolve(@PathVariable String id, @RequestBody Map<String,Object> body, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    GroupOrder o = orders.resolveDispute(id, new HumanActor(u.id(), u.role()), String.valueOf(body.get("outcome")), String.valueOf(body.getOrDefault("reasons", "")));
    return Map.of("success", true, "order", orders.view(o, u.id()));
  }

  private static int intOf(Object v, String field) {
    try { return new BigDecimal(String.valueOf(v).trim()).intValueExact(); } catch (Exception e) { throw new IllegalArgumentException(field + " must be a whole number"); }
  }
  private static BigDecimal dec(Object v) {
    try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception e) { throw new IllegalArgumentException("Amounts must be numbers"); }
  }
}
