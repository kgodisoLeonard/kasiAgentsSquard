package com.spaza.repository;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Human-owned stock rules the agent reads (when to warn, when it may draft an order).
 * Spending limits are NOT here: they live in the owner's SpendingMandate, enforced by MandateService.
 */
@Component
public class AgentPolicy {
  private int warningThreshold = 10;
  private int autoOrderThreshold = 5;
  private boolean autoOrderEnabled = true;

  public synchronized Map<String,Object> get() {
    return Map.of("warningThreshold", warningThreshold, "autoOrderThreshold", autoOrderThreshold, "autoOrderEnabled", autoOrderEnabled,
        "autoOrderMeans", "The agent may DRAFT a group order. It never pays: each member approves their own share and every payment passes the mandate check.");
  }

  public synchronized Map<String,Object> update(Map<String,Object> b) {
    int warning = num(b.getOrDefault("warningThreshold", warningThreshold)), auto = num(b.getOrDefault("autoOrderThreshold", autoOrderThreshold));
    if (warning < 1 || auto < 0 || auto > warning) throw new IllegalArgumentException("Auto-order threshold must be between 0 and the warning threshold");
    warningThreshold = warning; autoOrderThreshold = auto; autoOrderEnabled = Boolean.TRUE.equals(b.get("autoOrderEnabled"));
    return get();
  }

  public synchronized int warningThreshold() { return warningThreshold; }
  public synchronized int autoOrderThreshold() { return autoOrderThreshold; }
  public synchronized boolean autoOrderEnabled() { return autoOrderEnabled; }

  private static int num(Object v) {
    if (v instanceof Number n) return n.intValue();
    try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { throw new IllegalArgumentException("Thresholds must be whole numbers"); }
  }
}
