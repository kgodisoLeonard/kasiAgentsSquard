package com.spaza.controller;

import com.spaza.audit.AuditLog;
import com.spaza.repository.AgentPolicy;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Human-owner administration endpoint. It is deliberately outside the agent API namespace. */
@RestController @RequestMapping("/api/human/rules") @RequireRole("OWNER")
public class AgentRulesController {
  private final AgentPolicy policy;
  private final AuditLog audit;

  public AgentRulesController(AgentPolicy policy, AuditLog audit) { this.policy = policy; this.audit = audit; }

  @GetMapping public Map<String,Object> get(HttpServletRequest req) {
    return Map.of("success", true, "rules", policy.get(AuthInterceptor.current(req).id()), "note", "Only authorised human owners can change these guardrails.");
  }

  @PutMapping public Map<String,Object> update(@RequestBody Map<String,Object> body, HttpServletRequest req) {
    String ownerId = AuthInterceptor.current(req).id();
    Map<String,Object> before = policy.get(ownerId);
    Map<String,Object> after = policy.update(ownerId, body);
    audit.record(AuditLog.ActorType.HUMAN, ownerId, "AGENT_RULES_CHANGED", Map.of("previous", before), after);
    return Map.of("success", true, "rules", after, "message", "Human rules saved. The agent will follow them on the next stock check.");
  }
}
