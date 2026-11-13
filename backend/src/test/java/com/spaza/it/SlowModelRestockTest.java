package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.spaza.agent.ModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * With an LLM configured that is slow and then fails (like an overloaded Gemini), the till must answer at once, and the
 * owner's restock rule must still place the order in the background, visible as "on order" in the stock list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:slowmodel;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.restock.sweep-enabled=false"})
class SlowModelRestockTest {
  @TestConfiguration static class SlowModel {
    @Bean @Primary ModelClient slowFailingModel() {
      return new ModelClient() {
        @Override public boolean configured() { return true; }
        @Override public String provider() { return "test"; }
        @Override public String model() { return "slow-model"; }
        @Override public JsonNode generate(ObjectNode request) throws Exception { Thread.sleep(3000); throw new IllegalStateException("request timed out"); }
      };
    }
  }

  @LocalServerPort int port;

  @Test void tillAnswersImmediatelyAndTheRuleStillOrdersInTheBackground() throws Exception {
    Api thandi = new Api(port).login("Thandi's Spaza", "spaza123");
    JsonNode bread = FeatureApiTest.product(thandi, FeatureApiTest.BREAD);
    assertTrue(bread.get("onOrder").isNull());
    String orderId = thandi.post("/pos/orders", Map.of()).body().get("order").get("id").asText();

    long start = System.nanoTime();
    Api.Res added = thandi.post("/pos/orders/" + orderId + "/items", Map.of("productId", bread.get("id").asText(), "quantity", Math.max(1, bread.get("qty").asInt() - 3)));
    double seconds = (System.nanoTime() - start) / 1e9;
    assertEquals(200, added.status(), added.body().toString());
    assertTrue(seconds < 2.0, "the till must not wait for the model, took " + seconds + "s");
    assertEquals("AGENT_WORKING", added.body().get("restock").get("result").get("status").asText());

    JsonNode onOrder = null;
    for (int i = 0; i < 40 && (onOrder == null || onOrder.isNull()); i++) {
      Thread.sleep(500);
      onOrder = FeatureApiTest.product(thandi, FeatureApiTest.BREAD).get("onOrder");
    }
    assertNotNull(onOrder); assertFalse(onOrder.isNull(), "the rule should have ordered after the model failed");
    assertEquals(24, onOrder.get("quantity").asInt(), "quantity from Thandi's rule");
    assertEquals("SUP-002", onOrder.get("supplierId").asText());
  }
}
