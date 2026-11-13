package com.spaza.controller;

import com.spaza.audit.AuditLog;
import com.spaza.model.*;
import com.spaza.repository.*;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/agents") @RequireRole("OWNER")
public class AgentManagementController {
  private final AgentProfileRepository agents; private final AgentAuditEventRepository history; private final AuditLog audit;

  public AgentManagementController(AgentProfileRepository agents, AgentAuditEventRepository history, AuditLog audit) {
    this.agents = agents; this.history = history; this.audit = audit;
  }

  @GetMapping public Map<String,Object> list() { ensureDefault(); return Map.of("success", true, "agents", agents.findAll()); }

  @GetMapping("/{id}/audit") public Map<String,Object> events(@PathVariable String id) {
    ensureDefault(); return Map.of("success", true, "events", history.findTop20ByAgentIdOrderByCreatedAtDesc(id));
  }

  /** Pausing the agent blocks every agent action, including reads. Recorded in the hash-chained audit log. */
  @PostMapping("/{id}/enabled") public Map<String,Object> setEnabled(@PathVariable String id, @RequestBody Map<String,Object> body, HttpServletRequest req) {
    ensureDefault();
    AgentProfile a = agents.findById(id).orElseThrow(() -> new NoSuchElementException("Agent not found"));
    boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
    a.setEnabled(enabled); agents.save(a);
    String action = enabled ? "AGENT_ENABLED" : "AGENT_PAUSED";
    history.save(new AgentAuditEvent(id, action, "Changed by human owner"));
    audit.record(AuditLog.ActorType.HUMAN, AuthInterceptor.current(req).id(), action, Map.of("agentId", id), Map.of("enabled", enabled));
    return Map.of("success", true, "agent", a);
  }

  private void ensureDefault() {
    if (!agents.existsById("finance-agent")) {
      agents.save(new AgentProfile("finance-agent", "Spaza Finance Agent", "Shortage detection, group buying and guarded payment drafts", true));
      history.save(new AgentAuditEvent("finance-agent", "AGENT_CREATED", "Human-owned guardrails required for all actions"));
    }
  }
}
