package com.spaza.repository;

import com.spaza.agent.HumanRules;
import com.spaza.persistence.StateStore;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Each owner's Human Rules (when to warn, when the agent may order on its own), saved in the database.
 * They take precedence over restock rules; see {@link HumanRules}. Spending limits are NOT here: they live in the
 * owner's SpendingMandate, enforced by MandateService.
 */
@Component
public class AgentPolicy {
  private static final String KIND = "human-rules";
  private final StateStore store;
  private final Map<String,HumanRules> byOwner = new HashMap<>();

  public AgentPolicy(StateStore store) {
    this.store = store;
    byOwner.putAll(store.loadMap(KIND, HumanRules.class));
  }

  public synchronized HumanRules rules(String ownerId) { return byOwner.getOrDefault(ownerId, HumanRules.DEFAULT); }

  public synchronized Map<String,Object> get(String ownerId) { return view(rules(ownerId)); }

  public synchronized Map<String,Object> update(String ownerId, Map<String,Object> b) {
    HumanRules now = rules(ownerId);
    int warning = num(b.getOrDefault("warningThreshold", now.warningThreshold())), auto = num(b.getOrDefault("autoOrderThreshold", now.autoOrderThreshold()));
    if (warning < 1 || auto < 0 || auto > warning) throw new IllegalArgumentException("Auto-order threshold must be between 0 and the warning threshold");
    Object enabled = b.getOrDefault("autoOrderEnabled", now.autoOrderEnabled());
    HumanRules next = new HumanRules(warning, auto, Boolean.TRUE.equals(enabled) || "true".equals(String.valueOf(enabled)));
    byOwner.put(ownerId, next);
    store.save(KIND, ownerId, next);
    return view(next);
  }

  private static Map<String,Object> view(HumanRules r) {
    return Map.of("warningThreshold", r.warningThreshold(), "autoOrderThreshold", r.autoOrderThreshold(), "autoOrderEnabled", r.autoOrderEnabled(),
        "autoOrderMeans", "These rules come first. When automatic ordering is on, the agent may place a restock order (paid into escrow under "
            + "your restock rules and mandate) only when stock is below the auto-order level here AND below the item's restock rule. "
            + "When it is off, the agent never orders on its own; it only warns you.");
  }

  private static int num(Object v) {
    if (v instanceof Number n) return n.intValue();
    try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { throw new IllegalArgumentException("Thresholds must be whole numbers"); }
  }
}
