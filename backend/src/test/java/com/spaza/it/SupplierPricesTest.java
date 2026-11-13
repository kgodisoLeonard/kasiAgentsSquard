package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supplier price lists live in the database. Each supplier sees and edits only its own list, and the agent orders at
 * the current stored price, still within the owner's price limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:prices;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.llm.api-key=", "spaza.restock.sweep-enabled=false"})
class SupplierPricesTest {
  @LocalServerPort int port;

  static JsonNode priceOf(Api supplier, String product) {
    return FeatureApiTest.list(supplier.get("/supplier/prices").body().get("prices")).stream()
        .filter(p -> p.get("product").asText().equals(product)).findFirst().orElse(null);
  }

  @Test void suppliersManageTheirOwnPricesAndTheAgentUsesThem() {
    Api ubuntu = new Api(port).login("Ubuntu Foods Co-op", "supplier123");
    Api mokoena = new Api(port).login("Mokoena Meats (Pty) Ltd", "supplier123");
    Api thandi = new Api(port).login("Thandi's Spaza", "spaza123");        // rule: bread below 5 -> 24 from SUP-002, max R15

    // Each supplier sees only its own list, seeded with the demo prices.
    List<JsonNode> mine = FeatureApiTest.list(ubuntu.get("/supplier/prices").body().get("prices"));
    assertFalse(mine.isEmpty());
    assertTrue(mine.stream().allMatch(p -> p.get("supplierId").asText().equals("SUP-002")));
    assertEquals(13.00, priceOf(ubuntu, FeatureApiTest.BREAD).get("unitPrice").asDouble(), 0.001);
    assertNull(priceOf(mokoena, FeatureApiTest.BREAD), "Mokoena's list does not include the co-op's bread");

    // Only suppliers edit price lists, and only valid prices.
    assertEquals(403, thandi.put("/supplier/prices", Map.of("product", FeatureApiTest.BREAD, "unitPrice", 1)).status());
    assertEquals(400, ubuntu.put("/supplier/prices", Map.of("product", FeatureApiTest.BREAD, "unitPrice", 0)).status());

    // A price above the owner's limit: the agent does not order and tells the owner.
    assertEquals(200, ubuntu.put("/supplier/prices", Map.of("product", FeatureApiTest.BREAD, "unitPrice", 16.00)).status());
    JsonNode bread = FeatureApiTest.product(thandi, FeatureApiTest.BREAD);
    JsonNode tooDear = FeatureApiTest.sellAtTill(thandi, bread.get("id").asText(), bread.get("qty").asInt() - 3).get("restock").get("result");
    assertEquals("ESCALATED", tooDear.get("status").asText(), tooDear.toString());
    assertEquals("PRICE_ABOVE_LIMIT", tooDear.get("code").asText());

    // Price back within the limit: the agent orders at the new stored price.
    assertEquals(200, ubuntu.put("/supplier/prices", Map.of("product", FeatureApiTest.BREAD, "unitPrice", 12.50)).status());
    thandi.post("/pos/restock-check", Map.of());
    JsonNode onOrder = FeatureApiTest.product(thandi, FeatureApiTest.BREAD).get("onOrder");
    assertFalse(onOrder.isNull(), "agent should order once the price is within the limit");
    JsonNode order = FeatureApiTest.list(thandi.get("/group-orders").body().get("orders")).stream()
        .filter(o -> o.get("id").asText().equals(onOrder.get("orderId").asText())).findFirst().orElseThrow();
    assertEquals(12.50, order.get("unitPrice").asDouble(), 0.001);

    // Owners see the live list when setting restock rules.
    assertTrue(FeatureApiTest.list(thandi.get("/mandates").body().get("catalog")).stream()
        .anyMatch(i -> i.get("product").asText().equals(FeatureApiTest.BREAD) && i.get("unitPrice").asDouble() == 12.50));
  }
}
