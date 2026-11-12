package com.spaza.controller;
import com.spaza.repository.AgentPolicy;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/agent/rules")
public class AgentRulesController {
  private final AgentPolicy policy; public AgentRulesController(AgentPolicy policy){this.policy=policy;}
  @GetMapping public Map<String,Object> get(){return Map.of("success",true,"rules",policy.get(),"note","Only authorised human owners can change these guardrails.");}
  @PutMapping public Map<String,Object> update(@RequestBody Map<String,Object> body){return Map.of("success",true,"rules",policy.update(body),"message","Human rules saved. The agent will follow them on the next stock check.");}
}
