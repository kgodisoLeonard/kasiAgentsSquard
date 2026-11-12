package com.spaza.repository;

import org.springframework.stereotype.Component;
import java.util.*;

/** Human-owned guardrails. The agent reads these rules; it cannot change them. */
@Component
public class AgentPolicy {
  private int warningThreshold = 10;
  private int autoOrderThreshold = 5;
  private boolean autoOrderEnabled = false;
  private boolean xrplTestnetOnly = true;
  public synchronized Map<String,Object> get() { return Map.of("warningThreshold",warningThreshold,"autoOrderThreshold",autoOrderThreshold,"autoOrderEnabled",autoOrderEnabled,"xrplTestnetOnly",xrplTestnetOnly,"approvalRequiredAbove",1000); }
  public synchronized Map<String,Object> update(Map<String,Object> b) {
    int warning=((Number)b.getOrDefault("warningThreshold",warningThreshold)).intValue(); int auto=((Number)b.getOrDefault("autoOrderThreshold",autoOrderThreshold)).intValue();
    if(warning < 1 || auto < 0 || auto > warning) throw new IllegalArgumentException("Auto-order threshold must be between 0 and the warning threshold");
    warningThreshold=warning; autoOrderThreshold=auto; autoOrderEnabled=Boolean.TRUE.equals(b.get("autoOrderEnabled")); return get();
  }
  public synchronized int warningThreshold(){return warningThreshold;} public synchronized int autoOrderThreshold(){return autoOrderThreshold;} public synchronized boolean autoOrderEnabled(){return autoOrderEnabled;}
}
