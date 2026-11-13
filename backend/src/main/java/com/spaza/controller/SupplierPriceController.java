package com.spaza.controller;

import com.spaza.audit.AuditLog;
import com.spaza.finance.SupplierCatalog;
import com.spaza.finance.SupplierRegistry;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * A supplier's own price list. Suppliers only, and the supplier is always the signed-in account's own supplier
 * record, so one supplier can never read or change another's prices. The agent orders at these prices, still capped
 * by each owner's price limit in their restock rules. Every change is written to the audit log.
 */
@RestController
@RequestMapping("/api/supplier/prices")
@RequireRole("SUPPLIER")
public class SupplierPriceController {
  private final SupplierCatalog catalog;
  private final SupplierRegistry registry;
  private final AuditLog audit;

  public SupplierPriceController(SupplierCatalog catalog, SupplierRegistry registry, AuditLog audit) {
    this.catalog = catalog; this.registry = registry; this.audit = audit;
  }

  private SupplierRegistry.SupplierInfo me(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    return registry.findByAccount(u.id()).orElseThrow(() -> new NoSuchElementException("No supplier record is linked to this account"));
  }

  @GetMapping
  public Map<String,Object> mine(HttpServletRequest req) {
    SupplierRegistry.SupplierInfo s = me(req);
    return Map.of("success", true, "supplierId", s.id(), "supplierName", s.name(), "prices", catalog.bySupplier(s.id()).stream().map(SupplierCatalog.Item::view).toList());
  }

  @PutMapping
  public Map<String,Object> setPrice(@RequestBody Map<String,Object> body, HttpServletRequest req) {
    SupplierRegistry.SupplierInfo s = me(req);
    String product = String.valueOf(body.getOrDefault("product", "")).trim();
    BigDecimal price;
    try { price = new BigDecimal(String.valueOf(body.get("unitPrice")).trim()); } catch (Exception e) { throw new IllegalArgumentException("Price must be a number"); }
    Optional<BigDecimal> before = catalog.setPrice(s.id(), product, price);
    BigDecimal now = catalog.price(s.id(), product).orElseThrow().unitPrice();
    audit.record(AuditLog.ActorType.HUMAN, AuthInterceptor.current(req).id(), "SUPPLIER_PRICE_SET",
        Map.of("supplierId", s.id(), "product", product, "previous", before.map(BigDecimal::toPlainString).orElse("none")), Map.of("unitPrice", now));
    return Map.of("success", true, "prices", catalog.bySupplier(s.id()).stream().map(SupplierCatalog.Item::view).toList(),
        "message", product + " now R" + now.toPlainString() + (before.isPresent() ? " (was R" + before.get().toPlainString() + ")" : " (new product)"));
  }
}
