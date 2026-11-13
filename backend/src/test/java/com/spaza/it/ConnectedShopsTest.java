package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.spaza.inventory.ProductCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Six connected spaza shops stock the same products in different quantities; every product has a verified supplier and
 * one supplier is unverified; a group order flows through all member shops, which all see the same order and are told
 * about every step.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:connected;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.llm.api-key=", "spaza.restock.sweep-enabled=false"})
class ConnectedShopsTest {
  static final List<String> NEW_SHOPS = List.of("Lerato's Tuck Shop", "Bongani's Spaza", "Mama Zodwa's Shop", "Kagiso General Dealer");
  static final List<String> ALL_SHOPS = List.of("Thandi's Spaza", "Sipho's Corner Shop", "Lerato's Tuck Shop", "Bongani's Spaza", "Mama Zodwa's Shop", "Kagiso General Dealer");
  static final String SUGAR = "Sugar (2.5kg)";
  @LocalServerPort int port;

  Api owner(String shop) { return new Api(port).login(shop, "spaza123"); }
  static List<JsonNode> orders(Api a) { return FeatureApiTest.list(a.get("/group-orders").body().get("orders")); }
  static JsonNode order(Api a, String id) { return orders(a).stream().filter(o -> o.get("id").asText().equals(id)).findFirst().orElse(null); }
  static List<JsonNode> updates(Api a) { return FeatureApiTest.list(a.get("/platform/updates").body().get("updates")); }
  static boolean told(Api a, String orderId, String type) { return updates(a).stream().anyMatch(u -> u.get("orderId").asText().equals(orderId) && u.get("type").asText().equals(type)); }

  @Test void sixShopsShareTheSameProductsInDifferentQuantities() {
    Set<String> catalog = ProductCatalog.all().stream().map(ProductCatalog.Entry::name).collect(Collectors.toSet());
    List<Map<String,Integer>> shelves = new ArrayList<>();
    for (String shop : ALL_SHOPS) {
      Map<String,Integer> qty = new HashMap<>();
      FeatureApiTest.inventory(owner(shop)).forEach(p -> qty.put(p.get("name").asText(), p.get("qty").asInt()));
      assertEquals(catalog, qty.keySet(), shop + " must stock every catalog product");
      shelves.add(qty);
    }
    long differing = catalog.stream().filter(p -> shelves.stream().map(s -> s.get(p)).distinct().count() > 1).count();
    assertTrue(differing >= catalog.size() - 2, "quantities differ between shops (" + differing + " of " + catalog.size() + " products)");
  }

  @Test void everyProductHasAVerifiedSupplierAndOneSupplierIsUnverified() {
    Api thandi = owner("Thandi's Spaza");
    List<JsonNode> offers = FeatureApiTest.list(thandi.get("/platform/suppliers").body().get("suppliers"));
    Set<String> offered = offers.stream().map(o -> o.get("product").asText()).collect(Collectors.toSet());
    for (ProductCatalog.Entry e : ProductCatalog.all()) assertTrue(offered.contains(e.name()), "no verified offer for " + e.name());
    assertTrue(offers.stream().allMatch(o -> o.get("verified").asBoolean()), "only verified suppliers' offers are listed");
    assertTrue(offers.stream().map(o -> o.get("id").asText()).distinct().count() >= 6, "offers come from the suppliers' price lists");

    List<JsonNode> directory = FeatureApiTest.list(thandi.get("/platform/directory").body().get("suppliers"));
    JsonNode unverified = directory.stream().filter(s -> !s.get("verified").asBoolean()).findFirst().orElseThrow(() -> new AssertionError("no unverified supplier"));
    assertTrue(offers.stream().noneMatch(o -> o.get("id").asText().equals(unverified.get("id").asText())));
    Api.Res refused = thandi.post("/group-orders", Map.of("supplierId", unverified.get("id").asText(), "product", SUGAR, "unitPrice", 30, "quantity", 2, "minQuantity", 2));
    assertEquals(409, refused.status(), "an unverified supplier cannot receive orders: " + refused.body());
    assertTrue(new Api(port).login(unverified.get("businessName").asText(), "supplier123").get("/supplier/prices").status() == 200, "the unverified supplier can sign in");
  }

  @Test void aGroupOrderFlowsThroughEveryConnectedShop() {
    Map<String,Api> shop = new LinkedHashMap<>();
    for (String s : NEW_SHOPS) shop.put(s, owner(s));
    Api lerato = shop.get("Lerato's Tuck Shop");
    Map<String,Integer> qty = Map.of("Lerato's Tuck Shop", 3, "Bongani's Spaza", 2, "Mama Zodwa's Shop", 2, "Kagiso General Dealer", 1);
    Map<String,Integer> sugarBefore = new HashMap<>();
    shop.forEach((n, a) -> sugarBefore.put(n, FeatureApiTest.product(a, SUGAR).get("qty").asInt()));

    // The demo buying club already has a shared order waiting for all four shops.
    for (Api a : shop.values()) assertTrue(orders(a).stream().anyMatch(o -> o.get("shares").size() == 4 && o.get("status").asText().equals("COLLECTING")),
        "the seeded demo group order is shared by the four shops");

    // Lerato starts a group order for sugar and invites the other three.
    List<Map<String,Object>> invites = new ArrayList<>();
    for (String s : NEW_SHOPS.subList(1, 4)) invites.add(Map.of("ownerId", memberId(lerato, s), "quantity", qty.get(s)));
    Api.Res created = lerato.post("/group-orders", Map.of("supplierId", "SUP-002", "product", SUGAR, "unitPrice", 41.00, "quantity", 3,
        "minQuantity", 8, "deliveryFee", 40, "approvalMinutes", 120, "invite", invites));
    assertEquals(200, created.status(), created.body().toString());
    String id = created.body().get("order").get("id").asText();

    // Every member sees the same order; the invited shops are told.
    for (String s : NEW_SHOPS) {
      JsonNode o = order(shop.get(s), id);
      assertNotNull(o, s + " should see the order");
      assertEquals(4, o.get("shares").size()); assertEquals(8, o.get("totalQuantity").asInt());
      assertEquals(created.body().get("order").get("total").asDouble(), o.get("total").asDouble(), 0.001, "same totals for everyone");
    }
    for (String s : NEW_SHOPS.subList(1, 4)) assertTrue(told(shop.get(s), id, "INVITED"), s + " should be told about the invitation");

    // Each shop pays its own share; every other member sees it straight away.
    for (String s : NEW_SHOPS) {
      Api.Res paid = shop.get(s).post("/group-orders/" + id + "/approve", Map.of());
      assertEquals(200, paid.status(), s + ": " + paid.body());
      for (String other : NEW_SHOPS) {
        JsonNode share = FeatureApiTest.list(order(shop.get(other), id).get("shares")).stream()
            .filter(x -> x.get("ownerName").asText().equals(paid.body().get("order").get("yourShare").get("ownerName").asText())).findFirst().orElseThrow();
        assertEquals("FUNDED", share.get("status").asText(), other + " should see " + s + "'s payment");
      }
    }
    for (String s : NEW_SHOPS) assertEquals("FUNDED", order(shop.get(s), id).get("status").asText());
    assertTrue(told(shop.get("Kagiso General Dealer"), id, "SHARE_PAID"), "members are told when another shop pays");
    assertTrue(told(lerato, id, "ORDER_FUNDED"));

    // The supplier dispatches; all shops see it and are told.
    Api ubuntu = new Api(port).login("Ubuntu Foods Co-op", "supplier123");
    for (String a : List.of("ACCEPT", "DISPATCH")) assertEquals(200, ubuntu.post("/group-orders/" + id + "/supplier-action", Map.of("action", a)).status());
    for (String s : NEW_SHOPS) { assertEquals("IN_TRANSIT", order(shop.get(s), id).get("status").asText()); assertTrue(told(shop.get(s), id, "DISPATCHED")); }

    // Every shop confirms; escrow is released once the last one does, and each shop gets its own quantity.
    for (String s : NEW_SHOPS) assertEquals(200, shop.get(s).post("/group-orders/" + id + "/confirm-delivery", Map.of()).status());
    for (String s : NEW_SHOPS) {
      assertEquals("COMPLETED", order(shop.get(s), id).get("status").asText());
      assertTrue(told(shop.get(s), id, "COMPLETED"));
      assertEquals(sugarBefore.get(s) + qty.get(s), FeatureApiTest.product(shop.get(s), SUGAR).get("qty").asInt(), s + " receives its own share");
    }
  }

  static String memberId(Api a, String shopName) {
    return FeatureApiTest.list(a.get("/group-orders/members").body().get("members")).stream()
        .filter(m -> m.get("shop").asText().equals(shopName)).findFirst().orElseThrow().get("id").asText();
  }
}
