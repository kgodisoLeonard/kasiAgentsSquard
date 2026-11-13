package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.spaza.inventory.RestockSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The agent approves group-order shares within each shop's mandate and Human Rules; suppliers manage stock, accept or
 * decline orders; an order that exceeds a supplier's stock is redirected to another verified supplier.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:agentsupplier;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.llm.api-key=",
    "spaza.restock.sweep-enabled=true", "spaza.restock.sweep-initial-ms=3600000"})
class AgentSupplierFlowTest {
  static final String EGGS = "Eggs (18)";
  @LocalServerPort int port;
  @Autowired RestockSweeper sweeper;

  Api owner(String shop) { return new Api(port).login(shop, "spaza123"); }
  Api supplier(String name) { return new Api(port).login(name, "supplier123"); }

  static JsonNode waitForOrder(Api a, String id, java.util.function.Predicate<JsonNode> done) throws InterruptedException {
    JsonNode o = null;
    for (int i = 0; i < 40; i++) { o = ConnectedShopsTest.order(a, id); if (o != null && done.test(o)) return o; Thread.sleep(250); }
    return o;
  }
  static JsonNode share(JsonNode order, String ownerName) {
    return FeatureApiTest.list(order.get("shares")).stream().filter(s -> s.get("ownerName").asText().equals(ownerName)).findFirst().orElseThrow();
  }
  static JsonNode price(Api supplier, String product) { return SupplierPricesTest.priceOf(supplier, product); }

  @Test void agentApprovesSharesWithinMandateAndHumanRules() throws Exception {
    Api lerato = owner("Lerato's Tuck Shop"), bongani = owner("Bongani's Spaza"), zodwa = owner("Mama Zodwa's Shop"), kagiso = owner("Kagiso General Dealer");
    // Bongani switches automatic ordering off: the agent must leave his share for him to approve.
    assertEquals(200, bongani.put("/human/rules", Map.of("warningThreshold", 10, "autoOrderThreshold", 5, "autoOrderEnabled", false)).status());
    // Kagiso's share (60 x R41 = R2,460) is above his per-order cap of R2,000: the mandate blocks it.
    List<Map<String,Object>> invites = List.of(
        Map.of("ownerId", ConnectedShopsTest.memberId(lerato, "Bongani's Spaza"), "quantity", 2),
        Map.of("ownerId", ConnectedShopsTest.memberId(lerato, "Mama Zodwa's Shop"), "quantity", 2),
        Map.of("ownerId", ConnectedShopsTest.memberId(lerato, "Kagiso General Dealer"), "quantity", 60));
    Api.Res created = lerato.post("/group-orders", Map.of("supplierId", "SUP-002", "product", "Sugar (2.5kg)", "unitPrice", 41.00, "quantity", 3,
        "minQuantity", 7, "deliveryFee", 0, "approvalMinutes", 120, "invite", invites));
    assertEquals(200, created.status(), created.body().toString());
    String id = created.body().get("order").get("id").asText();

    JsonNode o = waitForOrder(lerato, id, x -> !share(x, "Zodwa Mthembu").get("status").asText().equals("PENDING")
        && !share(x, "Kagiso Molefe").get("status").asText().equals("PENDING"));
    assertEquals("FUNDED", share(o, "Lerato Dlamini").get("status").asText(), "drafter's share approved by the agent");
    assertEquals("FUNDED", share(o, "Zodwa Mthembu").get("status").asText(), "within mandate and rules: approved by the agent");
    assertTrue(share(o, "Zodwa Mthembu").get("fundedBy").asText().startsWith("AGENT"), "the agent paid it");
    assertEquals("PENDING", share(o, "Bongani Khumalo").get("status").asText(), "Human Rules off: left for the owner");
    assertEquals("BLOCKED", share(o, "Kagiso Molefe").get("status").asText(), "above the mandate's per-order cap: blocked");
    assertTrue(share(o, "Kagiso Molefe").get("blockedReason").asText().contains("cap"));
    assertTrue(ConnectedShopsTest.told(kagiso, id, "NEEDS_APPROVAL"), "the owner is told the agent could not approve");

    // The owner can still approve manually.
    assertEquals(200, bongani.post("/group-orders/" + id + "/approve", Map.of()).status());
    assertEquals("FUNDED", share(ConnectedShopsTest.order(zodwa, id), "Bongani Khumalo").get("status").asText());
  }

  @Test void sweepApprovesPendingSharesOfExistingOrders() {
    Api zodwa = owner("Mama Zodwa's Shop");
    JsonNode demo = ConnectedShopsTest.orders(zodwa).stream().filter(x -> x.get("reason").asText().startsWith("Demo buying club")).findFirst().orElseThrow();
    sweeper.sweep();
    JsonNode after = ConnectedShopsTest.order(zodwa, demo.get("id").asText());
    assertEquals("FUNDED", share(after, "Zodwa Mthembu").get("status").asText(), "the sweep lets the agent approve the seeded order");
  }

  @Test void suppliersManageStockAndItems() {
    Api ubuntu = supplier("Ubuntu Foods Co-op");
    assertEquals(200, ubuntu.put("/supplier/prices", Map.of("product", "Rooibos Tea (80 bags)", "unitPrice", 29.50, "stock", 40)).status());
    assertEquals(40, price(ubuntu, "Rooibos Tea (80 bags)").get("stock").asInt());
    assertEquals(200, ubuntu.put("/supplier/prices", Map.of("product", "Rooibos Tea (80 bags)", "unitPrice", 31.00, "stock", 55)).status());
    assertEquals(31.00, price(ubuntu, "Rooibos Tea (80 bags)").get("unitPrice").asDouble(), 0.001);
    assertEquals(55, price(ubuntu, "Rooibos Tea (80 bags)").get("stock").asInt());
    assertEquals(400, ubuntu.put("/supplier/prices", Map.of("product", "Rooibos Tea (80 bags)", "unitPrice", 31.00, "stock", -1)).status());
    Api.Res deleted = ubuntu.delete("/supplier/prices?product=" + java.net.URLEncoder.encode("Rooibos Tea (80 bags)", java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(200, deleted.status(), deleted.body().toString());
    assertNull(price(ubuntu, "Rooibos Tea (80 bags)"));
    assertEquals(404, ubuntu.delete("/supplier/prices?product=Nothing").status());
    assertEquals(403, owner("Thandi's Spaza").delete("/supplier/prices?product=Sugar").status());
  }

  @Test void orderAboveSupplierStockIsRedirected() throws Exception {
    Api mokoena = supplier("Mokoena Meats (Pty) Ltd"), ubuntu = supplier("Ubuntu Foods Co-op");
    Api sipho = owner("Sipho's Corner Shop");
    assertEquals(200, mokoena.put("/supplier/prices", Map.of("product", EGGS, "unitPrice", 39.00, "stock", 2)).status());
    int ubuntuBefore = price(ubuntu, EGGS).get("stock").asInt();
    Api.Res created = sipho.post("/group-orders", Map.of("supplierId", "SUP-001", "product", EGGS, "unitPrice", 39.00, "quantity", 8, "minQuantity", 8));
    assertEquals(200, created.status(), created.body().toString());
    JsonNode o = created.body().get("order");
    assertNotEquals("SUP-001", o.get("supplierId").asText(), "Mokoena only has 2 eggs: the order must go elsewhere");
    assertTrue(o.get("reason").asText().contains("Redirected"), o.get("reason").asText());
    if (o.get("supplierId").asText().equals("SUP-002")) assertEquals(ubuntuBefore - 8, price(ubuntu, EGGS).get("stock").asInt(), "stock is reserved");

    // Nobody has enough: refused.
    Api.Res none = sipho.post("/group-orders", Map.of("supplierId", "SUP-001", "product", EGGS, "unitPrice", 39.00, "quantity", 100000, "minQuantity", 1));
    assertEquals(409, none.status(), none.body().toString());
  }

  @Test void agentRestockRedirectsWhenTheRuleSupplierIsOutOfStock() {
    Api ubuntu = supplier("Ubuntu Foods Co-op"), thandi = owner("Thandi's Spaza");   // rule: bread below 5 -> 24 from SUP-002, max R15
    assertEquals(200, ubuntu.put("/supplier/prices", Map.of("product", FeatureApiTest.BREAD, "unitPrice", 13.00, "stock", 0)).status());
    JsonNode bread = FeatureApiTest.product(thandi, FeatureApiTest.BREAD);
    JsonNode r = FeatureApiTest.sellAtTill(thandi, bread.get("id").asText(), bread.get("qty").asInt() - 3).get("restock").get("result");
    assertEquals("ORDERED", r.get("status").asText(), r.toString());
    assertEquals("SUP-003", r.get("supplierId").asText(), "redirected to the bakery: allowed, in stock and within the R15 limit");
    assertTrue(r.get("unitPrice").asDouble() <= 15.0);
  }

  @Test void supplierCanAcceptOrDeclineAndDeclineRedirects() throws Exception {
    Api ubuntu = supplier("Ubuntu Foods Co-op"), kasi = owner("Kagiso General Dealer");
    Api.Res created = kasi.post("/group-orders", Map.of("supplierId", "SUP-002", "product", "Full Cream Milk (2L)", "unitPrice", 24.00, "quantity", 5, "minQuantity", 5));
    String id = created.body().get("order").get("id").asText();
    assertEquals("FUNDED", waitForOrder(kasi, id, x -> x.get("status").asText().equals("FUNDED")).get("status").asText(), "agent approved Kagiso's own order");
    double balanceBefore = kasi.get("/xrpl/status").body().get("rlusdBalance").asDouble();

    Api.Res declined = ubuntu.post("/group-orders/" + id + "/supplier-action", Map.of("action", "DECLINE", "reason", "Truck broken down"));
    assertEquals(200, declined.status(), declined.body().toString());
    assertEquals("CANCELLED", ConnectedShopsTest.order(kasi, id).get("status").asText());
    String redirectId = declined.body().get("redirectedTo").asText();
    JsonNode redirected = waitForOrder(kasi, redirectId, x -> x.get("status").asText().equals("FUNDED"));
    assertNotEquals("SUP-002", redirected.get("supplierId").asText(), "the declining supplier is skipped");
    assertTrue(redirected.get("reason").asText().contains("Truck broken down"));
    assertEquals("FUNDED", redirected.get("status").asText(), "the agent re-approved at the new supplier");
    assertTrue(ConnectedShopsTest.told(kasi, id, "SUPPLIER_DECLINED"));
    assertTrue(kasi.get("/xrpl/status").body().get("rlusdBalance").asDouble() <= balanceBefore + 0.01, "refund then new payment");
  }
}
