package com.spaza.config;

import com.spaza.agent.AgentGateway;
import com.spaza.audit.AuditLog;
import com.spaza.finance.*;
import com.spaza.model.Product;
import com.spaza.repository.AgentProfileRepository;
import com.spaza.repository.AppUserRepository;
import com.spaza.repository.InMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableScheduling
public class FinanceConfig {
  @Bean Clock clock() { return Clock.systemUTC(); }

  @Bean SupplierRegistry supplierRegistry(AppUserRepository users) { return new SupplierRegistry(users::findAll); }

  @Bean SimulatedXrplLedger ledger(Clock clock, SupplierRegistry suppliers) { return new SimulatedXrplLedger(clock, suppliers::isVerifiedPayoutAddress); }

  @Bean MandateService mandateService(SpendingMandateRepository repo, SupplierRegistry suppliers, AuditLog audit, Clock clock) {
    return new MandateService(repo, suppliers, audit, clock);
  }

  @Bean GroupOrderService groupOrderService(MandateService mandates, SupplierRegistry suppliers, SimulatedXrplLedger ledger, AuditLog audit, Clock clock,
                                            InMemoryStore store, @Value("${spaza.delivery-window-hours:48}") long deliveryHours) {
    GroupOrderService service = new GroupOrderService(mandates, suppliers, ledger, audit, clock, Duration.ofHours(deliveryHours));
    // When escrow is released the delivered stock is added to the shop inventory (shared demo inventory).
    service.onCompleted(order -> store.findByName(order.product()).ifPresent(p -> p.setQty(p.getQty() + order.fundedQuantity())));
    return service;
  }

  /** The agent gets the MandateReader interface only, so it has no code path to change a mandate. */
  @Bean AgentGateway agentGateway(MandateService mandates, GroupOrderService orders, SupplierRegistry suppliers, InMemoryStore store,
                                  AgentProfileRepository agents, AuditLog audit) {
    MandateReader readOnly = mandates::snapshot;
    return new AgentGateway(readOnly, orders, suppliers, () -> java.util.List.<Product>copyOf(store.findAll()),
        () -> agents.findById(AgentGateway.AGENT_ID).map(a -> a.isEnabled()).orElse(true), audit);
  }
}
