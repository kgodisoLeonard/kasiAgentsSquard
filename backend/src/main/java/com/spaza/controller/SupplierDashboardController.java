package com.spaza.controller;

import com.spaza.finance.GroupOrder;
import com.spaza.finance.GroupOrderService;
import com.spaza.finance.SupplierRegistry;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/**
 * The supplier's dashboard: its orders by delivery status, and its revenue.
 * A supplier is paid only when the spaza owner accepts the delivery (escrow released). "Delivered" by the supplier alone
 * is shown as awaiting the owner's confirmation, never as revenue. Orders still collecting buyers are not shown.
 */
@RestController
@RequestMapping("/api/supplier/dashboard")
@RequireRole("SUPPLIER")
public class SupplierDashboardController {
  private static final ZoneId SA = ZoneId.of("Africa/Johannesburg");
  private final GroupOrderService orders;
  private final SupplierRegistry registry;

  public SupplierDashboardController(GroupOrderService orders, SupplierRegistry registry) { this.orders = orders; this.registry = registry; }

  private static BigDecimal sum(GroupOrder o, GroupOrder.ShareStatus status) {
    return o.shares().stream().filter(s -> s.status() == status).map(GroupOrder.Share::total).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String,Object>> timeline(GroupOrderService orders, GroupOrder o) {
    return (List<Map<String,Object>>) orders.view(o, null).get("timeline");
  }

  /** Which bucket an order is in for the supplier, or null if the supplier never saw it (still collecting / cancelled before funding). */
  private String bucket(GroupOrder o) {
    return switch (o.status()) {
      case COMPLETED -> "PAID";
      case DELIVERED_PENDING_CONFIRMATION -> "AWAITING_CONFIRMATION";
      case FUNDED, ACCEPTED, IN_TRANSIT -> "TO_DELIVER";
      case REFUNDED, DISPUTED -> "NOT_DELIVERED";
      case CANCELLED -> timeline(orders, o).stream().anyMatch(t -> "SUPPLIER_DECLINED".equals(t.get("action"))) ? "NOT_DELIVERED" : null;
      default -> null;
    };
  }

  @GetMapping
  public Map<String,Object> dashboard(HttpServletRequest req) {
    SupplierRegistry.SupplierInfo me = registry.findByAccount(AuthInterceptor.current(req).id())
        .orElseThrow(() -> new NoSuchElementException("No supplier record is linked to this account"));
    List<GroupOrder> mine = orders.all().stream().filter(o -> o.supplierId().equals(me.id()) && bucket(o) != null).toList();

    BigDecimal paid = BigDecimal.ZERO, awaiting = BigDecimal.ZERO, toDeliver = BigDecimal.ZERO, refunded = BigDecimal.ZERO, disputed = BigDecimal.ZERO;
    Map<String,int[]> counts = new LinkedHashMap<>();
    Map<String,BigDecimal> values = new LinkedHashMap<>();
    for (String k : List.of("PAID", "AWAITING_CONFIRMATION", "TO_DELIVER", "NOT_DELIVERED")) { counts.put(k, new int[1]); values.put(k, BigDecimal.ZERO); }
    LocalDate today = LocalDate.now(SA);
    Map<LocalDate,BigDecimal> byDay = new TreeMap<>();
    for (int i = 13; i >= 0; i--) byDay.put(today.minusDays(i), BigDecimal.ZERO);
    Map<String,BigDecimal> productRevenue = new HashMap<>();
    Map<String,Integer> productUnits = new HashMap<>();
    List<Map<String,Object>> recent = new ArrayList<>();

    for (GroupOrder o : mine) {
      String b = bucket(o);
      BigDecimal released = sum(o, GroupOrder.ShareStatus.RELEASED), held = sum(o, GroupOrder.ShareStatus.FUNDED), back = sum(o, GroupOrder.ShareStatus.REFUNDED);
      paid = paid.add(released); refunded = refunded.add(back);
      switch (b) {
        case "AWAITING_CONFIRMATION" -> awaiting = awaiting.add(held);
        case "TO_DELIVER" -> toDeliver = toDeliver.add(held);
        case "NOT_DELIVERED" -> { if (o.status() == GroupOrder.Status.DISPUTED) disputed = disputed.add(held); }
        default -> { }
      }
      counts.get(b)[0]++;
      values.put(b, values.get(b).add(switch (b) { case "PAID" -> released; case "NOT_DELIVERED" -> back.add(held); default -> held; }));
      if (released.signum() > 0) {
        productRevenue.merge(o.product(), released, BigDecimal::add);
        productUnits.merge(o.product(), o.shares().stream().filter(s -> s.status() == GroupOrder.ShareStatus.RELEASED).mapToInt(GroupOrder.Share::quantity).sum(), Integer::sum);
        timeline(orders, o).stream().filter(t -> "ORDER_COMPLETED".equals(t.get("action"))).findFirst().ifPresent(t -> {
          LocalDate day = Instant.parse(String.valueOf(t.get("at"))).atZone(SA).toLocalDate();
          if (byDay.containsKey(day)) byDay.merge(day, released, BigDecimal::add);
        });
      }
      Map<String,Object> v = new LinkedHashMap<>();
      v.put("id", o.id()); v.put("product", o.product()); v.put("status", o.status().name()); v.put("bucket", b);
      v.put("units", o.totalQuantity()); v.put("value", b.equals("PAID") ? released : b.equals("NOT_DELIVERED") ? back.add(held) : held);
      v.put("shops", o.shares().size());
      recent.add(v);
    }
    Collections.reverse(recent);

    Map<String,String> labels = Map.of("PAID", "Delivered and paid", "AWAITING_CONFIRMATION", "Delivered, waiting for the owner to accept",
        "TO_DELIVER", "Paid into escrow, to deliver", "NOT_DELIVERED", "Not delivered (declined, refunded or disputed)");
    List<Map<String,Object>> statuses = new ArrayList<>();
    counts.forEach((k, n) -> statuses.add(Map.of("key", k, "label", labels.get(k), "orders", n[0], "value", values.get(k))));
    List<Map<String,Object>> days = new ArrayList<>();
    byDay.forEach((d, v) -> days.add(Map.of("date", d.toString(), "revenue", v)));
    List<Map<String,Object>> top = productRevenue.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed()).limit(6)
        .map(e -> Map.<String,Object>of("product", e.getKey(), "revenue", e.getValue(), "units", productUnits.get(e.getKey()))).toList();

    Map<String,Object> revenue = new LinkedHashMap<>();
    revenue.put("paid", paid); revenue.put("awaitingConfirmation", awaiting); revenue.put("toDeliver", toDeliver);
    revenue.put("refunded", refunded); revenue.put("disputed", disputed);
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("success", true); r.put("supplierId", me.id()); r.put("supplierName", me.name());
    r.put("revenue", revenue); r.put("statuses", statuses); r.put("revenueByDay", days); r.put("topProducts", top);
    r.put("orders", recent.subList(0, Math.min(recent.size(), 10)));
    r.put("rule", "You are paid when the spaza owner accepts the delivery. Until then the money waits in escrow.");
    return r;
  }
}
