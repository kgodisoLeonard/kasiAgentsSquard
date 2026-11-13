package com.spaza.controller;

import com.spaza.agent.AgentGateway;
import com.spaza.finance.MandateService;
import com.spaza.finance.SupplierCatalog;
import com.spaza.model.Product;
import com.spaza.repository.InMemoryStore;
import com.spaza.finance.MandateService.HumanActor;
import com.spaza.finance.SupplierRegistry;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Human-only mandate administration. This namespace is separate from the agent API and the agent holds no session. */
@RestController
@RequestMapping("/api/mandates")
@RequireRole("OWNER")
public class MandateController {
  public static final String STEP_UP_ACTION = "change spending mandate";
  private final MandateService mandates;
  private final SupplierRegistry suppliers;
  private final AuthService auth;
  private final SupplierCatalog catalog;
  private final InMemoryStore store;
  private final AgentGateway agent;

  public MandateController(MandateService mandates, SupplierRegistry suppliers, AuthService auth, SupplierCatalog catalog, InMemoryStore store, AgentGateway agent) {
    this.mandates = mandates; this.suppliers = suppliers; this.auth = auth; this.catalog = catalog; this.store = store; this.agent = agent;
  }

  @GetMapping
  public Map<String,Object> mine(HttpServletRequest req) {
    AuthUser user = AuthInterceptor.current(req);
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("success", true);
    r.put("mandate", mandates.snapshot(user.id()).orElse(null));
    r.put("suppliers", suppliers.all().stream().map(s -> Map.of("id", s.id(), "name", s.name(), "verified", s.verified(), "onTrial", s.onTrial(), "screeningHit", s.sanctionsHit())).toList());
    r.put("stepUpAction", STEP_UP_ACTION);
    r.put("catalog", catalog.all().stream().map(SupplierCatalog.Item::view).toList());
    r.put("inventory", store.findAll().stream().map(p -> Map.of("name", p.getName(), "qty", p.getQty())).toList());
    return r;
  }

  @PutMapping
  public Map<String,Object> update(@RequestBody Map<String,Object> body, HttpServletRequest req, @RequestHeader(value = "X-Step-Up", required = false) String stepUp) {
    AuthUser user = AuthInterceptor.current(req);
    auth.requireStepUp(user, STEP_UP_ACTION, stepUp);
    List<String> allowed = new ArrayList<>();
    if (body.get("allowedSuppliers") instanceof List<?> list) list.forEach(x -> allowed.add(String.valueOf(x)));
    LocalDate validUntil;
    try { validUntil = LocalDate.parse(String.valueOf(body.get("validUntil"))); } catch (Exception e) { throw new IllegalArgumentException("Valid-until must be a date (YYYY-MM-DD)"); }
    var saved = mandates.update(new HumanActor(user.id(), user.role()), user.id(), amount(body.get("perOrderCap")), amount(body.get("dailyCap")),
        allowed, validUntil, !Boolean.FALSE.equals(body.get("enforceOrderDeadline")));
    return Map.of("success", true, "mandate", mandates.view(saved), "message", "Mandate saved. The agent sees the new limits immediately but cannot change them.");
  }

  /** Agent budget and automatic restock rules (stock level, order quantity, approved supplier, price limit). Owner only, with step-up. */
  @PutMapping("/autonomy")
  public Map<String,Object> updateAutonomy(@RequestBody Map<String,Object> body, HttpServletRequest req, @RequestHeader(value = "X-Step-Up", required = false) String stepUp) {
    AuthUser user = AuthInterceptor.current(req);
    auth.requireStepUp(user, STEP_UP_ACTION, stepUp);
    List<MandateService.RestockRule> rules = new ArrayList<>();
    if (body.get("restockRules") instanceof List<?> list) for (Object o : list) if (o instanceof Map<?,?> m) {
      String product = String.valueOf(m.get("product")).trim(), supplierId = String.valueOf(m.get("supplierId")).trim();
      if (store.findByName(product).filter(p -> p.getName().equalsIgnoreCase(product)).isEmpty()) throw new IllegalArgumentException("Unknown product: " + product);
      if (catalog.price(supplierId, product).isEmpty()) throw new IllegalArgumentException(supplierId + " does not sell " + product);
      rules.add(new MandateService.RestockRule(product, whole(m.get("reorderBelow"), "Reorder level"), whole(m.get("orderQuantity"), "Order quantity"), supplierId, amount(m.get("maxUnitPrice"))));
    }
    var saved = mandates.updateAutonomy(new HumanActor(user.id(), user.role()), user.id(), amount(body.getOrDefault("monthlyAgentBudget", 0)), rules);
    // Items that are already below their new reorder level are checked straight away.
    List<Map<String,Object>> restock = new ArrayList<>();
    for (MandateService.RestockRule r : rules)
      store.findByName(r.product()).filter(p -> p.getQty() < r.reorderBelow()).ifPresent(p -> restock.add(agent.execute("PLACE_RESTOCK_ORDER", Map.of("product", p.getName()), user.id())));
    return Map.of("success", true, "mandate", mandates.view(saved), "restock", restock, "message", "Restock rules saved. The agent will reorder these items automatically within your budget.");
  }

  private static int whole(Object v, String field) {
    try { return new BigDecimal(String.valueOf(v).trim()).intValueExact(); } catch (Exception e) { throw new IllegalArgumentException(field + " must be a whole number"); }
  }

  private static BigDecimal amount(Object v) {
    try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception e) { throw new IllegalArgumentException("Caps must be numbers"); }
  }
}
