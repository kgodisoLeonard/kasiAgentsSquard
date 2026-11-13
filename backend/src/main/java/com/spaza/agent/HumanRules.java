package com.spaza.agent;

/**
 * The owner's Human Rules. They take precedence over the agent's restock rules: when automatic ordering is off the agent
 * never orders on its own, and it only orders when stock is below autoOrderThreshold (as well as the restock rule's level).
 */
public record HumanRules(int warningThreshold, int autoOrderThreshold, boolean autoOrderEnabled) {
  public static final HumanRules DEFAULT = new HumanRules(10, 5, true);
  /** No human limits (unit tests of the restock logic alone). */
  public static final HumanRules UNRESTRICTED = new HumanRules(Integer.MAX_VALUE, Integer.MAX_VALUE, true);

  /** Why the Human Rules stop an automatic order at this stock level, or null when they allow it. */
  public String blocks(int stock) {
    if (!autoOrderEnabled) return "Automatic ordering is switched off in your Human Rules. Not ordered.";
    if (stock >= autoOrderThreshold) return "Your Human Rules allow automatic orders only below " + autoOrderThreshold + " (stock is " + stock + ").";
    return null;
  }
}
