package com.spaza.inventory;

import com.spaza.agent.AgentGateway;
import com.spaza.finance.GroupOrderService;
import com.spaza.finance.MandateService;
import com.spaza.model.AppUser;
import com.spaza.model.Product;
import com.spaza.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Safety net for automatic restocking. Till sales trigger the agent immediately; this sweep also catches stock that is
 * already below an owner's rule without a sale (e.g. new rules, seeded stock, or after a restart), for every shop
 * separately. It uses the same guarded agent action, so the owner's rule, mandate, budget and the one-open-order-per-
 * product guard all still apply. Products with an order already in progress are skipped before calling the agent.
 */
@Component
@ConditionalOnProperty(name = "spaza.restock.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class RestockSweeper {
  private static final Logger log = LoggerFactory.getLogger(RestockSweeper.class);
  private final AppUserRepository users;
  private final MandateService mandates;
  private final InventoryService inventory;
  private final GroupOrderService orders;
  private final AgentGateway agent;
  private final com.spaza.repository.AgentPolicy humanRules;

  public RestockSweeper(AppUserRepository users, MandateService mandates, InventoryService inventory, GroupOrderService orders, AgentGateway agent,
                        com.spaza.repository.AgentPolicy humanRules) {
    this.humanRules = humanRules;
    this.users = users; this.mandates = mandates; this.inventory = inventory; this.orders = orders; this.agent = agent;
  }

  @Scheduled(initialDelayString = "${spaza.restock.sweep-initial-ms:20000}", fixedDelayString = "${spaza.restock.sweep-ms:120000}")
  public void scheduled() {
    try { sweep(); } catch (RuntimeException e) { log.warn("Restock sweep failed: {}", e.getMessage()); }
  }

  /** Returns the agent results for every product and group-order share it acted on. */
  public List<Map<String,Object>> sweep() {
    List<Map<String,Object>> results = new ArrayList<>();
    // Group orders still collecting: the agent approves pending shares for owners whose Human Rules allow it.
    for (String[] pending : orders.pendingShares()) {
      if (!humanRules.rules(pending[1]).autoOrderEnabled()) continue;
      results.add(agent.execute("APPROVE_GROUP_SHARE", Map.of("orderId", pending[0]), pending[1]));
    }
    for (AppUser owner : users.findAll()) {
      if (!"OWNER".equals(owner.getRole())) continue;
      for (MandateService.RestockRule rule : mandates.restockRules(owner.getId())) {
        Optional<Product> p = inventory.byName(owner.getId(), rule.product()).filter(x -> x.getName().equalsIgnoreCase(rule.product()));
        if (p.isEmpty() || p.get().getQty() >= rule.reorderBelow()) continue;
        if (orders.openOrderForMember(owner.getId(), p.get().getName()).isPresent()) continue;
        if (humanRules.rules(owner.getId()).blocks(p.get().getQty()) != null) continue;   // Human Rules come first
        Map<String,Object> r = agent.execute("PLACE_RESTOCK_ORDER", Map.of("product", p.get().getName()), owner.getId());
        log.info("Restock sweep: {} for {} -> {}", p.get().getName(), owner.getBusinessName(), r.get("result") instanceof Map<?,?> m ? m.get("status") : r.get("code"));
        results.add(r);
      }
    }
    return results;
  }
}
