package com.spaza.controller;

import com.spaza.agent.AgentGateway;
import com.spaza.agent.LlmAgent;
import com.spaza.finance.GroupOrderService;
import com.spaza.model.Product;
import com.spaza.repository.AgentPolicy;
import com.spaza.repository.AppUserRepository;
import com.spaza.inventory.InventoryService;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Owner-facing view of the Spaza Finance Agent. Every agent action goes through AgentGateway (allow-list,
 * mandate read-only, audit-logged). The owner's session identifies who the agent works for; the agent itself
 * holds no credentials.
 */
@RestController
@RequestMapping("/api/agent")
@RequireRole("OWNER")
public class AgentController {
  private final InventoryService store; private final AgentPolicy policy; private final PlatformController platform;
  private final AgentGateway agent; private final GroupOrderService orders; private final AppUserRepository users; private final LlmAgent llm;

  public AgentController(InventoryService store, AgentPolicy policy, PlatformController platform, AgentGateway agent, GroupOrderService orders, AppUserRepository users, LlmAgent llm) {
    this.store = store; this.policy = policy; this.platform = platform; this.agent = agent; this.orders = orders; this.users = users; this.llm = llm;
  }

  @GetMapping("/llm")
  public Map<String,Object> llmStatus() { return Map.of("success", true, "llm", llm.status()); }

  /** The owner asks the LLM agent to do something. It can only act through the guarded agent tools. */
  @PostMapping("/llm/run")
  public Map<String,Object> llmRun(@RequestBody Map<String,Object> body, HttpServletRequest req) {
    String message = String.valueOf(body.getOrDefault("message", "")).trim();
    if (message.isEmpty()) throw new IllegalArgumentException("Tell the agent what to do");
    if (message.length() > 1000) throw new IllegalArgumentException("Keep the request under 1000 characters");
    return Map.of("success", true, "run", llm.run(AuthInterceptor.current(req).id(), message, "owner-request"));
  }

  @GetMapping("/rules")
  public Map<String,Object> rules(HttpServletRequest req) {
    return Map.of("success", true, "rules", policy.get(AuthInterceptor.current(req).id()), "access", "READ_ONLY",
        "allowedActions", Arrays.stream(AgentGateway.Action.values()).map(Enum::name).toList(),
        "restrictedResources", List.of("mandate changes", "wallets", "bank_details", "identity_documents", "secrets"),
        "systemPrompt", AgentGateway.SYSTEM_PROMPT);
  }

  /** Runs one agent action on behalf of the signed-in owner. Blocked actions return allowed=false with a reason. */
  @PostMapping("/actions")
  public Map<String,Object> act(@RequestBody Map<String,Object> body, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    @SuppressWarnings("unchecked") Map<String,Object> params = body.get("params") instanceof Map<?,?> m ? (Map<String,Object>) m : Map.of();
    return agent.execute(String.valueOf(body.get("action")), params, u.id());
  }

  /** Shortage scan with group-order recommendations. If the owner's human rules allow it, the agent drafts orders (never pays). */
  @GetMapping("/shortages")
  public Map<String,Object> shortages(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    Map<String,Object> scan = agent.execute("DETECT_SHORTAGES", Map.of(), u.id());
    List<Map<String,Object>> recommendations = new ArrayList<>();
    int drafted = 0;   // at most one new group-order draft per scan, so a dashboard visit never floods the other shops
    for (Product p : store.list(u.id())) {
      if (p.getQty() > policy.rules(u.id()).warningThreshold()) continue;
      Optional<Map<String,Object>> offer = platform.offerFor(p.getName());
      Map<String,Object> rec = new LinkedHashMap<>();
      rec.put("product", p.getName()); rec.put("stock", p.getQty()); rec.put("reorderLevel", p.getLowStock());
      rec.put("source", "inventory record " + p.getId());
      if (offer.isEmpty()) { rec.put("status", "NO_VERIFIED_OFFER"); rec.put("confidence", "LOW"); rec.put("note", "No verified supplier sells this item. Escalated to the owner."); recommendations.add(rec); continue; }
      Map<String,Object> o = offer.get();
      rec.put("supplierId", o.get("id")); rec.put("supplierName", o.get("name")); rec.put("offerProduct", o.get("product"));
      rec.put("unitPrice", o.get("price")); rec.put("minOrder", o.get("minOrder")); rec.put("confidence", "HIGH");
      rec.put("draftParams", draftParams(u, o));
      boolean auto = drafted == 0 && policy.rules(u.id()).blocks(p.getQty()) == null;
      var existing = orders.openOrderForMember(u.id(), p.getName()).or(() -> orders.openOrderFor(String.valueOf(o.get("id")), String.valueOf(o.get("product"))));
      if (existing.isPresent()) { rec.put("status", "ORDER_OPEN"); rec.put("orderId", existing.get().id()); }
      else if (auto) {
        Map<String,Object> result = agent.execute("DRAFT_GROUP_ORDER", draftParams(u, o), u.id());
        rec.put("status", Boolean.TRUE.equals(result.get("allowed")) ? "AGENT_DRAFTED" : "DRAFT_BLOCKED");
        rec.put("agentResult", result);
        drafted++;
      } else rec.put("status", "RECOMMENDED");
      recommendations.add(rec);
    }
    return Map.of("success", true, "scan", scan, "recommendations", recommendations, "humanRules", policy.get(u.id()),
        "note", "The agent only drafts. Each member approves their own share; payment then passes the mandate check before escrow.");
  }

  @GetMapping("/notifications")
  public Map<String,Object> notifications(HttpServletRequest req) {
    return Map.of("success", true, "notifications", agent.notificationsFor(AuthInterceptor.current(req).id()));
  }

  /** The community is every verified owner (simulated "nearby shops"); quantities are split so the group meets the supplier minimum. */
  private Map<String,Object> draftParams(AuthUser u, Map<String,Object> offer) {
    List<Map<String,Object>> members = new ArrayList<>();
    var owners = users.findAll().stream().filter(x -> "OWNER".equals(x.getRole()) && (x.isVerified() || x.getId().equals(u.id()))).toList();
    int min = ((Number) offer.get("minOrder")).intValue();
    int each = Math.max(1, (int) Math.ceil(min / (double) Math.max(1, owners.size())));
    for (var o : owners) members.add(Map.of("ownerId", o.getId(), "ownerName", o.getName(), "quantity", each));
    Map<String,Object> p = new LinkedHashMap<>();
    p.put("supplierId", offer.get("id")); p.put("product", offer.get("product")); p.put("unitPrice", offer.get("price"));
    p.put("minQuantity", min); p.put("deliveryFee", 250); p.put("approvalMinutes", 60 * 18); p.put("members", members);
    return p;
  }
}
