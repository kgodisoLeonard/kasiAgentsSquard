package com.spaza.controller;

import com.spaza.finance.MandateService;
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

  public MandateController(MandateService mandates, SupplierRegistry suppliers, AuthService auth) { this.mandates = mandates; this.suppliers = suppliers; this.auth = auth; }

  @GetMapping
  public Map<String,Object> mine(HttpServletRequest req) {
    AuthUser user = AuthInterceptor.current(req);
    Map<String,Object> r = new LinkedHashMap<>();
    r.put("success", true);
    r.put("mandate", mandates.snapshot(user.id()).orElse(null));
    r.put("suppliers", suppliers.all().stream().map(s -> Map.of("id", s.id(), "name", s.name(), "verified", s.verified(), "onTrial", s.onTrial(), "screeningHit", s.sanctionsHit())).toList());
    r.put("stepUpAction", STEP_UP_ACTION);
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

  private static BigDecimal amount(Object v) {
    try { return new BigDecimal(String.valueOf(v).trim()); } catch (Exception e) { throw new IllegalArgumentException("Caps must be numbers"); }
  }
}
