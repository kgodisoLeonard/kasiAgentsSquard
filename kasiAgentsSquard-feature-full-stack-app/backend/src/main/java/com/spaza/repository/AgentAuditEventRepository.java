package com.spaza.repository;
import com.spaza.model.AgentAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AgentAuditEventRepository extends JpaRepository<AgentAuditEvent,String> { List<AgentAuditEvent> findTop20ByAgentIdOrderByCreatedAtDesc(String agentId); }
