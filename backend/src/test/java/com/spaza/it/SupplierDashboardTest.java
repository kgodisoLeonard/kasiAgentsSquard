package com.spaza.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The supplier dashboard: orders by delivery status and revenue. A supplier is paid only when the spaza owner accepts
 * the delivery; "delivered" by the supplier alone is not revenue yet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:supplierdash;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.llm.api-key=", "spaza.restock.sweep-enabled=false"})
class SupplierDashboardTest {
  @LocalServerPort int port;

  /** Sipho orders from Ubuntu; the agent pays his share within his mandate. Returns the funded order id. */
  String fundedOrder(Api sipho, String product, double price, int qty) throws Exception {
    Api.Res r = sipho.post("/group-orders", Map.of("supplierId", "SUP-002", "product", product, "unitPrice", price, "quantity", qty, "minQuantity", qty));
    assertEquals(200, r.status(), r.body().toString());
    String id = r.body().get("order").get("id").asText();
    assertEquals("FUNDED", AgentSupplierFlowTest.waitForOrder(sipho, id, o -> o.get("status").asText().equals("FUNDED")).get("status").asText());
    return id;
  }

  @Test void dashboardShowsDeliveryStatusAndRevenueOnlyAfterTheOwnerAccepts() throws Exception {
    Api sipho = new Api(port).login("Sipho's Corner Shop", "spaza123"), ubuntu = new Api(port).login("Ubuntu Foods Co-op", "supplier123");
    // Other orders (e.g. the seeded demo club order) may already exist: compare against a starting snapshot.
    JsonNode start = ubuntu.get("/supplier/dashboard").body();
    java.util.function.BiFunction<JsonNode,String,Double> rev = (d, k) -> d.get("revenue").get(k).asDouble();
    java.util.function.BiFunction<JsonNode,String,Integer> cnt = (d, k) -> { for (JsonNode s : d.get("statuses")) if (s.get("key").asText().equals(k)) return s.get("orders").asInt(); return 0; };
    String paid = fundedOrder(sipho, "Rice (2kg)", 33.00, 4);            // R132: delivered and accepted -> revenue
    String waiting = fundedOrder(sipho, "Samp (2kg)", 21.00, 5);         // R105: delivered, owner has not accepted -> not revenue yet
    String toDeliver = fundedOrder(sipho, "Cake Flour (2.5kg)", 27.00, 3); // R81: paid into escrow, not sent
    String declined = fundedOrder(sipho, "Atchar (380g)", 19.00, 2);     // R38: supplier declines -> refunded

    for (String id : new String[]{paid, waiting})
      for (String a : new String[]{"ACCEPT", "DISPATCH", "DELIVER"}) assertEquals(200, ubuntu.post("/group-orders/" + id + "/supplier-action", Map.of("action", a)).status());
    JsonNode before = ubuntu.get("/supplier/dashboard").body();
    assertEquals(rev.apply(start, "paid"), rev.apply(before, "paid"), 0.001, "marking an order delivered does not pay the supplier");

    assertEquals(200, sipho.post("/group-orders/" + paid + "/confirm-delivery", Map.of()).status());   // the owner accepts the delivery
    assertEquals(200, ubuntu.post("/group-orders/" + declined + "/supplier-action", Map.of("action", "DECLINE", "reason", "Out of jars")).status());

    Api.Res res = ubuntu.get("/supplier/dashboard");
    assertEquals(200, res.status(), res.body().toString());
    JsonNode d = res.body();
    assertEquals(132.0, rev.apply(d, "paid") - rev.apply(start, "paid"), 0.001, "paid only for the delivery the owner accepted");
    assertEquals(105.0, rev.apply(d, "awaitingConfirmation") - rev.apply(start, "awaitingConfirmation"), 0.001);
    assertEquals(81.0, rev.apply(d, "toDeliver") - rev.apply(start, "toDeliver"), 0.001);
    assertEquals(38.0, rev.apply(d, "refunded") - rev.apply(start, "refunded"), 0.001);
    assertEquals(1, cnt.apply(d, "PAID") - cnt.apply(start, "PAID"));
    assertEquals(1, cnt.apply(d, "AWAITING_CONFIRMATION") - cnt.apply(start, "AWAITING_CONFIRMATION"));
    assertEquals(1, cnt.apply(d, "TO_DELIVER") - cnt.apply(start, "TO_DELIVER"));
    assertEquals(1, cnt.apply(d, "NOT_DELIVERED") - cnt.apply(start, "NOT_DELIVERED"));
    assertTrue(d.get("revenueByDay").size() >= 14, "a daily series for the chart");
    assertEquals("Rice (2kg)", d.get("topProducts").get(0).get("product").asText(), "top product by revenue received");

    assertEquals(403, sipho.get("/supplier/dashboard").status(), "owners have no supplier dashboard");
  }
}
