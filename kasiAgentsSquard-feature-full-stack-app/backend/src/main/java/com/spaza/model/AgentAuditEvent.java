package com.spaza.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="agent_audit_events")
public class AgentAuditEvent {
  @Id @GeneratedValue(strategy=GenerationType.UUID) private String id;
  @Column(nullable=false) private String agentId;
  @Column(nullable=false) private String action;
  @Column(length=2000) private String detail;
  @Column(nullable=false) private Instant createdAt;
  protected AgentAuditEvent() {}
  public AgentAuditEvent(String agentId,String action,String detail){this.agentId=agentId;this.action=action;this.detail=detail;this.createdAt=Instant.now();}
  public String getId(){return id;} public String getAgentId(){return agentId;} public String getAction(){return action;} public String getDetail(){return detail;} public Instant getCreatedAt(){return createdAt;}
}
