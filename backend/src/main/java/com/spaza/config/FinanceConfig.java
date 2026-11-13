package com.spaza.config;

import com.spaza.agent.AgentGateway;
import com.spaza.audit.AuditLog;
import com.spaza.finance.*;
import com.spaza.inventory.InventoryService;
import com.spaza.model.Product;
import com.spaza.persistence.StateStore;
import com.spaza.repository.AgentProfileRepository;
import com.spaza.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/** Finance and agent wiring. Every stateful service writes through to the database (StateStore) and reloads at startup. */
@Configuration
@EnableScheduling
public class FinanceConfig {
  @Bean Clock clock() { return Clock.systemUTC(); }

  /** Supplier lookups happen several times per order; the user list is re-read at most every 30 seconds. */
  @Bean SupplierRegistry supplierRegistry(AppUserRepository users, StateStore store) {
    return new SupplierRegistry(new com.spaza.persistence.Ttl<>(Duration.ofSeconds(30), users::findAll), store);
  }

  @Bean SupplierCatalog supplierCatalog() { return new SupplierCatalog(); }

  /** Google AI Studio (Gemini). Key from the GEMINI_API_KEY environment variable; never committed. */
  @Bean com.spaza.agent.ModelClient modelClient(@Value("${spaza.llm.api-key:}") String key, @Value("${spaza.llm.model:gemini-flash-latest}") String model,
                                                @Value("${spaza.llm.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl) {
    return new com.spaza.agent.GeminiClient(key, model, baseUrl);
  }

  /** The agent only ever sees the stock of the shop it is working for. */
  private static Function<String,List<Product>> shopStock(InventoryService inventory) { return owner -> List.copyOf(inventory.list(owner)); }

  @Bean com.spaza.agent.LlmAgent llmAgent(com.spaza.agent.ModelClient model, AgentGateway gateway, MandateService mandates, InventoryService inventory,
                                          GroupOrderService orders, SupplierRegistry suppliers, AuditLog audit, Clock clock) {
    return new com.spaza.agent.LlmAgent(model, gateway, mandates::snapshot, shopStock(inventory), orders, suppliers, audit, clock);
  }

  @Bean SimulatedXrplLedger ledger(Clock clock, SupplierRegistry suppliers, StateStore store) {
    return new SimulatedXrplLedger(clock, suppliers::isVerifiedPayoutAddress, store);
  }

  @Bean MandateService mandateService(SpendingMandateRepository repo, SupplierRegistry suppliers, AuditLog audit, Clock clock, StateStore store) {
    return new MandateService(repo, suppliers, audit, clock, store);
  }

  @Bean GroupOrderService groupOrderService(MandateService mandates, SupplierRegistry suppliers, SimulatedXrplLedger ledger, AuditLog audit, Clock clock,
                                            InventoryService inventory, StateStore store, @Value("${spaza.delivery-window-hours:48}") long deliveryHours) {
    GroupOrderService service = new GroupOrderService(mandates, suppliers, ledger, audit, clock, Duration.ofHours(deliveryHours), store);
    // When escrow is released, each buyer's delivered quantity goes into that buyer's own shop.
    service.onCompleted(order -> order.shares().stream().filter(s -> s.status() == GroupOrder.ShareStatus.RELEASED)
        .forEach(s -> inventory.receive(s.ownerId(), order.product(), s.quantity())));
    return service;
  }

  /** The owner's pause switch, re-read at most every 5 seconds. */
  private static com.spaza.persistence.Ttl<Boolean> agentEnabled(AgentProfileRepository agents) {
    return new com.spaza.persistence.Ttl<>(Duration.ofSeconds(5), () -> agents.findById(AgentGateway.AGENT_ID).map(a -> a.isEnabled()).orElse(true));
  }

  /** The agent gets the MandateReader interface only, so it has no code path to change a mandate. */
  @Bean AgentGateway agentGateway(MandateService mandates, GroupOrderService orders, SupplierRegistry suppliers, InventoryService inventory,
                                  AgentProfileRepository agents, AuditLog audit, SupplierCatalog catalog, AppUserRepository users, StateStore store, Clock clock,
                                  com.spaza.repository.AgentPolicy humanRules) {
    MandateReader readOnly = mandates::snapshot;
    return new AgentGateway(readOnly, orders, suppliers, shopStock(inventory),
        agentEnabled(agents)::get, audit, catalog, users::findById, store, clock, humanRules::rules);
  }
}
