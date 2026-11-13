package com.spaza.controller;

import com.spaza.audit.AuditEntry;
import com.spaza.audit.AuditLog;
import com.spaza.security.RequireRole;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Read-only view of the hash-chained audit log. There are no write, update or delete endpoints. */
@RestController
@RequestMapping("/api/audit")
@RequireRole({"OWNER", "ADMIN"})
public class AuditController {
  private final AuditLog audit;
  public AuditController(AuditLog audit) { this.audit = audit; }

  @GetMapping public Map<String,Object> recent() {
    return Map.of("success", true, "entries", audit.recent().stream().map(AuditController::view).toList());
  }

  @GetMapping("/verify") public Map<String,Object> verify() {
    Map<String,Object> r = new LinkedHashMap<>(audit.verify()); r.put("success", true); return r;
  }

  static Map<String,Object> view(AuditEntry e) {
    Map<String,Object> v = new LinkedHashMap<>();
    v.put("seq", e.getSeq()); v.put("at", e.getTimestamp()); v.put("actorType", e.getActorType()); v.put("actor", e.getActorId());
    v.put("action", e.getAction()); v.put("inputs", e.getInputs()); v.put("outputs", e.getOutputs()); v.put("prevHash", e.getPrevHash()); v.put("hash", e.getHash());
    return v;
  }
}
