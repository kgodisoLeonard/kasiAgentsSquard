package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.*;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feature tests over real HTTP against a fresh in-memory database (no Supabase, no Gemini).
 * Demo accounts: owners "Thandi's Spaza" (restock rules for bread, maize, milk) and "Sipho's Corner Shop";
 * suppliers Mokoena Meats (SUP-001) and Ubuntu Foods Co-op (SUP-002); reviewer "Spaza Trust & Safety 1".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:features;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.llm.api-key=",
    "spaza.restock.sweep-enabled=false"})
class FeatureApiTest {
  static final String BREAD = "White Bread (700g)";
  @LocalServerPort int port;
  // Signed in once per class: the app rate-limits sign-ins to 30 a minute per network.
  static Api thandi, sipho, mokoena, ubuntu, reviewer;

  @BeforeEach void signIn() {
    if (thandi != null) return;
    thandi = new Api(port).login("Thandi's Spaza", "spaza123");
    sipho = new Api(port).login("Sipho's Corner Shop", "spaza123");
    mokoena = new Api(port).login("Mokoena Meats (Pty) Ltd", "supplier123");
    ubuntu = new Api(port).login("Ubuntu Foods Co-op", "supplier123");
    reviewer = new Api(port).login("Spaza Trust & Safety 1", "review123");
  }

  // ------------------------------------------------------------------ helpers

  static List<JsonNode> list(JsonNode array) { return array == null ? List.of() : StreamSupport.stream(array.spliterator(), false).toList(); }
  static List<JsonNode> inventory(Api a) { Api.Res r = a.get("/pos/inventory"); assertEquals(200, r.status(), r.body().toString()); return list(r.body().get("inventory")); }
  static JsonNode product(Api a, String name) { return inventory(a).stream().filter(p -> p.get("name").asText().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " missing")); }

  /** Sells via the till exactly as the POS screen does: open order, add items, take payment. Returns the add-item response. */
  static JsonNode sellAtTill(Api a, String productId, int qty) {
    String orderId = a.post("/pos/orders", Map.of()).body().get("order").get("id").asText();
    Api.Res added = a.post("/pos/orders/" + orderId + "/items", Map.of("productId", productId, "quantity", qty));
    assertEquals(200, added.status(), added.body().toString());
    Api.Res closed = a.post("/pos/orders/" + orderId + "/close", Map.of("paymentMethod", "card"));
    assertEquals(200, closed.status(), closed.body().toString());
    return added.body();
  }

  // ------------------------------------------------------------------ features

  @Test void everyOwnerHasTheirOwnSeededStock() {
    List<JsonNode> a = inventory(thandi), b = inventory(sipho);
    assertTrue(a.size() >= 20, "Thandi should have at least 20 seeded products, has " + a.size());
    assertTrue(b.size() >= 20, "Sipho should have at least 20 seeded products, has " + b.size());
    Set<String> idsA = new HashSet<>(); a.forEach(p -> idsA.add(p.get("id").asText()));
    assertTrue(b.stream().noneMatch(p -> idsA.contains(p.get("id").asText())), "owners must not share product records");
    assertNotNull(product(thandi, BREAD), "essentials with restock rules are always stocked");
  }

  @Test void ownersCannotSeeOrChangeEachOthersStock() {
    JsonNode thandiTea = inventory(thandi).get(0);
    String id = thandiTea.get("id").asText(); int before = thandiTea.get("qty").asInt();
    String siphoOrder = sipho.post("/pos/orders", Map.of()).body().get("order").get("id").asText();
    assertEquals(404, sipho.post("/pos/orders/" + siphoOrder + "/items", Map.of("productId", id, "quantity", 1)).status(), "Sipho cannot sell Thandi's product");
    assertEquals(404, sipho.put("/pos/inventory/" + id + "/price", Map.of("price", 1)).status(), "Sipho cannot reprice Thandi's product");
    assertEquals(before, product(thandi, thandiTea.get("name").asText()).get("qty").asInt());
  }

  @Test void rolesOnlyReachTheirOwnScreens() {
    assertEquals(403, mokoena.get("/pos/inventory").status(), "suppliers have no till");
    assertEquals(403, reviewer.get("/pos/inventory").status(), "reviewers have no till");
    assertEquals(403, ubuntu.get("/platform/suppliers/recommended").status(), "recommendations are for owners");
    assertEquals(403, thandi.post("/group-orders/GO-0/supplier-action", Map.of("action", "ACCEPT")).status(), "owners cannot act as suppliers");
    assertEquals(403, thandi.post("/group-orders/GO-0/resolve", Map.of("outcome", "REFUND", "reason", "x")).status(), "owners cannot decide disputes");
  }

  /** Till sale -> agent orders per the owner's rule -> no duplicate -> supplier delivers -> stock lands in that shop only -> owner rates supplier. */
  @Test void restockLifecycleFromTillToDeliveryAndRating() {
    JsonNode bread = product(thandi, BREAD);
    int siphoBreadBefore = product(sipho, BREAD).get("qty").asInt();
    int sell = Math.max(1, bread.get("qty").asInt() - 3);               // leaves 3, below Thandi's rule level of 5
    JsonNode added = sellAtTill(thandi, bread.get("id").asText(), sell);
    JsonNode restock = added.get("restock");
    assertNotNull(restock, "selling below the rule level must trigger the agent: " + added);
    assertEquals("ORDERED", restock.get("result").get("status").asText(), restock.toString());
    assertEquals(24, restock.get("result").get("quantity").asInt(), "quantity comes from the owner's rule");
    assertEquals("SUP-002", restock.get("result").get("supplierId").asText(), "supplier comes from the owner's rule");
    assertTrue(restock.get("result").get("unitPrice").asDouble() <= 15.0, "price within the rule's limit");
    String orderId = restock.get("result").get("orderId").asText();

    // The stock list says bread is already on order, so the owner can see why nothing more is ordered.
    JsonNode onOrder = product(thandi, BREAD).get("onOrder");
    assertNotNull(onOrder, "inventory must show the pending restock order");
    assertEquals(orderId, onOrder.get("orderId").asText());

    // Selling more while that order is open must not create a second order.
    JsonNode again = sellAtTill(thandi, bread.get("id").asText(), 1).get("restock");
    assertEquals("SKIPPED", again.get("result").get("status").asText());

    // Only the order's own supplier sees it.
    assertTrue(list(ubuntu.get("/group-orders").body().get("orders")).stream().anyMatch(o -> o.get("id").asText().equals(orderId)));
    assertTrue(list(mokoena.get("/group-orders").body().get("orders")).stream().noneMatch(o -> o.get("id").asText().equals(orderId)));
    assertEquals(403, mokoena.post("/group-orders/" + orderId + "/supplier-action", Map.of("action", "ACCEPT")).status(), "another supplier cannot touch it");

    int stockBeforeDelivery = product(thandi, BREAD).get("qty").asInt();
    for (String action : List.of("ACCEPT", "DISPATCH", "DELIVER"))
      assertEquals(200, ubuntu.post("/group-orders/" + orderId + "/supplier-action", Map.of("action", action)).status(), action);
    Api.Res confirmed = thandi.post("/group-orders/" + orderId + "/confirm-delivery", Map.of());
    assertEquals(200, confirmed.status(), confirmed.body().toString());
    assertEquals(stockBeforeDelivery + 24, product(thandi, BREAD).get("qty").asInt(), "delivered stock is added to the buyer's shop");
    assertEquals(siphoBreadBefore, product(sipho, BREAD).get("qty").asInt(), "other shops are untouched");
    assertTrue(product(thandi, BREAD).get("onOrder") == null || product(thandi, BREAD).get("onOrder").isNull(), "nothing on order after delivery");

    // The owner rates the supplier; recommendations reflect it.
    Api.Res rated = thandi.post("/platform/suppliers/SUP-002/ratings", Map.of("orderId", orderId, "rating", 5, "comment", "On time"));
    assertEquals(200, rated.status(), rated.body().toString());
    Api.Res rec = thandi.get("/platform/suppliers/recommended");
    assertEquals(200, rec.status(), rec.body().toString());
    List<JsonNode> ranked = list(rec.body().get("suppliers"));
    assertFalse(ranked.isEmpty());
    JsonNode ubuntuRow = ranked.stream().filter(s -> s.get("id").asText().equals("SUP-002")).findFirst().orElseThrow();
    assertEquals(5.0, ubuntuRow.get("rating").asDouble(), 0.01);
    assertEquals(1, ubuntuRow.get("ratingCount").asInt());
    for (int i = 1; i < ranked.size(); i++)
      assertTrue(ranked.get(i - 1).get("score").asDouble() >= ranked.get(i).get("score").asDouble(), "sorted best first");
  }

  @Test void signingInNeedsNoSmsCode() {
    Api a = new Api(port).login("Sipho's Corner Shop", "spaza123");
    assertTrue(a.lastLogin.hasNonNull("accessToken"), "business name + password must sign straight in: " + a.lastLogin);
    assertFalse(a.lastLogin.has("demoCode"), "no SMS code is sent at sign-in");
    assertEquals(200, a.get("/pos/inventory").status());
  }

  @Test void incomeReflectsPaymentWhenTheOrderIsClosed() {
    JsonNode before = sipho.get("/pos/sales").body();
    double incomeBefore = before.get("today").get("revenue").asDouble(), totalBefore = before.get("revenue").asDouble();
    JsonNode item = inventory(sipho).stream().filter(p -> p.get("qty").asInt() > 3 && p.get("onOrder").isNull()).findFirst().orElseThrow();
    String orderId = sipho.post("/pos/orders", Map.of()).body().get("order").get("id").asText();
    sipho.post("/pos/orders/" + orderId + "/items", Map.of("productId", item.get("id").asText(), "quantity", 2));
    assertEquals(incomeBefore, sipho.get("/pos/sales").body().get("today").get("revenue").asDouble(), 0.001, "an open cart is not income yet");
    Api.Res closed = sipho.post("/pos/orders/" + orderId + "/close", Map.of("paymentMethod", "card"));
    double paid = closed.body().get("sale").get("subtotal").asDouble();
    assertEquals(2 * item.get("price").asDouble(), paid, 0.001);
    JsonNode after = sipho.get("/pos/sales").body();
    assertEquals(incomeBefore + paid, after.get("today").get("revenue").asDouble(), 0.001, "today's income includes the payment");
    assertEquals(totalBefore + paid, after.get("revenue").asDouble(), 0.001, "total income includes the payment");
    assertEquals(before.get("today").get("count").asInt() + 1, after.get("today").get("count").asInt());
  }

  @Test void salesBelongToTheShopThatMadeThem() {
    JsonNode item = inventory(sipho).stream().filter(p -> p.get("qty").asInt() > 2).findFirst().orElseThrow();
    sellAtTill(sipho, item.get("id").asText(), 1);
    int siphoSales = sipho.get("/pos/sales").body().get("count").asInt();
    assertTrue(siphoSales >= 1);
    List<JsonNode> thandiSales = list(thandi.get("/pos/sales").body().get("sales"));
    assertTrue(thandiSales.stream().flatMap(s -> list(s.get("items")).stream()).noneMatch(i -> i.get("productId").asText().equals(item.get("id").asText())),
        "Sipho's sale must not appear in Thandi's history");
  }
}
