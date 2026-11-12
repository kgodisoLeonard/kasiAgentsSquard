package com.spaza.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="agent_profiles")
public class AgentProfile {
  @Id private String id;
  @Column(nullable=false) private String name;
  @Column(nullable=false) private String role;
  @Column(nullable=false) private boolean enabled;
  @Column(nullable=false) private Instant updatedAt;
  protected AgentProfile() {}
  public AgentProfile(String id,String name,String role,boolean enabled){this.id=id;this.name=name;this.role=role;this.enabled=enabled;this.updatedAt=Instant.now();}
  public String getId(){return id;} public String getName(){return name;} public String getRole(){return role;} public boolean isEnabled(){return enabled;} public Instant getUpdatedAt(){return updatedAt;}
  public void setEnabled(boolean enabled){this.enabled=enabled;this.updatedAt=Instant.now();}
}
