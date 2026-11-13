package com.spaza.controller;
import com.spaza.model.*;
import com.spaza.repository.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/agents")
public class AgentManagementController {
  private final AgentProfileRepository agents; private final AgentAuditEventRepository audit;
  public AgentManagementController(AgentProfileRepository agents,AgentAuditEventRepository audit){this.agents=agents;this.audit=audit;}
  @GetMapping public Map<String,Object> list(){ ensureDefault(); return Map.of("success",true,"agents",agents.findAll()); }
  @GetMapping("/{id}/audit") public Map<String,Object> history(@PathVariable String id){ensureDefault();return Map.of("success",true,"events",audit.findTop20ByAgentIdOrderByCreatedAtDesc(id));}
  @PostMapping("/{id}/enabled") public Map<String,Object> setEnabled(@PathVariable String id,@RequestBody Map<String,Object> body){ensureDefault();AgentProfile a=agents.findById(id).orElseThrow(()->new NoSuchElementException("Agent not found"));boolean enabled=Boolean.TRUE.equals(body.get("enabled"));a.setEnabled(enabled);agents.save(a);audit.save(new AgentAuditEvent(id,enabled?"AGENT_ENABLED":"AGENT_PAUSED","Changed by human owner"));return Map.of("success",true,"agent",a);}
  private void ensureDefault(){if(!agents.existsById("finance-agent")){agents.save(new AgentProfile("finance-agent","Spaza Finance Agent","Shortage detection, group buying and guarded payment drafts",true));audit.save(new AgentAuditEvent("finance-agent","AGENT_CREATED","Human-owned guardrails required for all actions"));}}
}
