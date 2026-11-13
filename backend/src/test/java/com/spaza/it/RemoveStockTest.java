package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owners can write off units (damaged, expired, stolen) or delete a product from their own stock list. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:removestock;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.llm.api-key=", "spaza.restock.sweep-enabled=false"})
class RemoveStockTest {
  @LocalServerPort int port;

  static JsonNode anyProduct(Api owner, int minQty, String except) {
    return FeatureApiTest.inventory(owner).stream()
        .filter(p -> p.get("qty").asInt() >= minQty && p.get("onOrder").isNull() && !p.get("name").asText().equals(except)).findFirst().orElseThrow();
  }

  @Test void writeOffUnitsAndDeleteProducts() {
    Api sipho = new Api(port).login("Sipho's Corner Shop", "spaza123");
    Api thandi = new Api(port).login("Thandi's Spaza", "spaza123");
    JsonNode item = anyProduct(sipho, 5, FeatureApiTest.BREAD);
    String id = item.get("id").asText(); int qty = item.get("qty").asInt();

    // Write off some units.
    Api.Res off = sipho.post("/pos/inventory/" + id + "/remove", Map.of("quantity", 2, "reason", "Expired"));
    assertEquals(200, off.status(), off.body().toString());
    assertEquals(qty - 2, off.body().get("item").get("qty").asInt());
    assertEquals(qty - 2, FeatureApiTest.product(sipho, item.get("name").asText()).get("qty").asInt());
    assertEquals(400, sipho.post("/pos/inventory/" + id + "/remove", Map.of("quantity", 0, "reason", "x")).status(), "quantity must be at least 1");
    assertTrue(sipho.post("/pos/inventory/" + id + "/remove", Map.of("quantity", qty + 100, "reason", "x")).status() >= 400, "cannot remove more than in stock");
    assertTrue(FeatureApiTest.list(thandi.get("/audit").body().get("entries")).stream()
        .anyMatch(e -> e.get("action").asText().equals("STOCK_WRITTEN_OFF")), "write-offs are audited");

    // Another owner can neither write off nor delete it.
    assertEquals(404, thandi.post("/pos/inventory/" + id + "/remove", Map.of("quantity", 1, "reason", "x")).status());
    assertEquals(404, thandi.delete("/pos/inventory/" + id).status());

    // Delete the product from the stock list.
    Api.Res deleted = sipho.delete("/pos/inventory/" + id);
    assertEquals(200, deleted.status(), deleted.body().toString());
    assertTrue(FeatureApiTest.inventory(sipho).stream().noneMatch(p -> p.get("id").asText().equals(id)));
    assertEquals(404, sipho.delete("/pos/inventory/" + id).status());

    // A product with a restock order on the way cannot be deleted (the delivery needs somewhere to go).
    JsonNode bread = FeatureApiTest.product(sipho, FeatureApiTest.BREAD);
    JsonNode restock = FeatureApiTest.sellAtTill(sipho, bread.get("id").asText(), bread.get("qty").asInt() - 2).get("restock");
    assertEquals("ORDERED", restock.get("result").get("status").asText(), restock.toString());
    Api.Res blocked = sipho.delete("/pos/inventory/" + bread.get("id").asText());
    assertEquals(409, blocked.status(), blocked.body().toString());
  }
}
