package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.spaza.SpazaApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts the whole app, changes state, shuts it down and starts it again on the same database.
 * Everything a shop depends on must still be there: stock, sales, the open restock order (so the agent does not
 * order twice), the escrowed money, the agent's monthly spend and the signed-in session.
 */
class RestartPersistenceTest {
  static final String BREAD = "White Bread (700g)";

  static ConfigurableApplicationContext start() {
    // Command-line arguments outrank application.properties (plain builder properties would not).
    return new SpringApplicationBuilder(SpazaApplication.class).run(
        "--server.port=0",
        "--spring.datasource.url=jdbc:h2:mem:restart;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "--spring.datasource.username=sa", "--spring.datasource.password=",
        "--spaza.supabase.url=", "--spaza.supabase.key=", "--spaza.llm.api-key=",
        "--spaza.restock.sweep-enabled=false");
  }

  static int port(ConfigurableApplicationContext ctx) { return ((WebServerApplicationContext) ctx).getWebServer().getPort(); }

  @Test void stateSurvivesARestart() {
    String token, orderId; int breadAfterSale, salesCount; double agentSpent, balance;
    ConfigurableApplicationContext first = start();
    try {
      Api thandi = new Api(port(first)).login("Thandi's Spaza", "spaza123");
      JsonNode bread = FeatureApiTest.product(thandi, BREAD);
      JsonNode restock = FeatureApiTest.sellAtTill(thandi, bread.get("id").asText(), Math.max(1, bread.get("qty").asInt() - 3)).get("restock");
      assertEquals("ORDERED", restock.get("result").get("status").asText(), restock.toString());
      orderId = restock.get("result").get("orderId").asText();
      breadAfterSale = FeatureApiTest.product(thandi, BREAD).get("qty").asInt();
      salesCount = thandi.get("/pos/sales").body().get("count").asInt();
      agentSpent = thandi.get("/mandates").body().get("mandate").get("agentSpentThisMonth").asDouble();
      balance = thandi.get("/xrpl/status").body().get("rlusdBalance").asDouble();
      token = thandi.token();
      assertTrue(agentSpent > 0);
      assertEquals(200, thandi.put("/human/rules", Map.of("warningThreshold", 12, "autoOrderThreshold", 4, "autoOrderEnabled", true)).status());
      assertEquals(200, new Api(port(first)).login("Ubuntu Foods Co-op", "supplier123").put("/supplier/prices", Map.of("product", "Maize Meal (10kg)", "unitPrice", 79.00)).status());
    } finally { first.close(); }

    ConfigurableApplicationContext second = start();
    try {
      Api thandi = new Api(port(second)).withToken(token);
      assertEquals(200, thandi.get("/pos/inventory").status(), "signed-in session survives a restart");
      assertEquals(breadAfterSale, FeatureApiTest.product(thandi, BREAD).get("qty").asInt(), "stock survives");
      assertEquals(4, thandi.get("/human/rules").body().get("rules").get("autoOrderThreshold").asInt(), "Human Rules survive");
      assertEquals(79.00, SupplierPricesTest.priceOf(new Api(port(second)).login("Ubuntu Foods Co-op", "supplier123"), "Maize Meal (10kg)").get("unitPrice").asDouble(), 0.001,
          "supplier price changes survive");
      assertEquals(salesCount, thandi.get("/pos/sales").body().get("count").asInt(), "sales survive");
      assertEquals(agentSpent, thandi.get("/mandates").body().get("mandate").get("agentSpentThisMonth").asDouble(), 0.001, "agent budget use survives");
      assertEquals(balance, thandi.get("/xrpl/status").body().get("rlusdBalance").asDouble(), 0.001, "wallet balance survives");
      List<JsonNode> orders = FeatureApiTest.list(thandi.get("/group-orders").body().get("orders"));
      JsonNode order = orders.stream().filter(o -> o.get("id").asText().equals(orderId)).findFirst().orElseThrow(() -> new AssertionError("restock order lost on restart"));
      assertEquals("FUNDED", order.get("status").asText());
      JsonNode again = FeatureApiTest.sellAtTill(thandi, FeatureApiTest.product(thandi, BREAD).get("id").asText(), 1).get("restock");
      assertEquals("SKIPPED", again.get("result").get("status").asText(), "the open order still blocks a duplicate after restart");

      // Delivery still completes and pays the supplier after the restart.
      Api ubuntu = new Api(port(second)).login("Ubuntu Foods Co-op", "supplier123");
      for (String action : List.of("ACCEPT", "DISPATCH", "DELIVER"))
        assertEquals(200, ubuntu.post("/group-orders/" + orderId + "/supplier-action", Map.of("action", action)).status(), action);
      assertEquals(200, thandi.post("/group-orders/" + orderId + "/confirm-delivery", Map.of()).status());
    } finally { second.close(); }
  }
}
