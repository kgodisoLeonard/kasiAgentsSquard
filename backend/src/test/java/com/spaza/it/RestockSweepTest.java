package com.spaza.it;

import com.spaza.inventory.InventoryService;
import com.spaza.inventory.RestockSweeper;
import com.spaza.model.Product;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The background sweep reorders stock that is already low without any till sale, per shop, once. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sweep;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spaza.supabase.url=", "spaza.supabase.key=", "spaza.llm.api-key=",
    "spaza.restock.sweep-enabled=true", "spaza.restock.sweep-initial-ms=3600000"})
class RestockSweepTest {
  static final String MILK = "Full Cream Milk (2L)";
  @Autowired RestockSweeper sweeper;
  @Autowired InventoryService inventory;

  @SuppressWarnings("unchecked")
  static List<Map<String,Object>> forProduct(List<Map<String,Object>> results, String product) {
    return results.stream().filter(r -> r.get("result") instanceof Map<?,?> m && product.equals(m.get("product"))).toList();
  }

  @SuppressWarnings("unchecked")
  @Test void sweepOrdersLowStockForEachShopOnce() {
    Product siphoMilk = inventory.byName("owner-demo-2", MILK).orElseThrow();
    if (siphoMilk.getQty() > 1) inventory.take("owner-demo-2", siphoMilk.getId(), siphoMilk.getQty() - 1);   // down to 1: below Sipho's rule (4), no sale recorded
    int thandiMilkBefore = inventory.byName("owner-demo", MILK).orElseThrow().getQty();

    List<Map<String,Object>> first = sweeper.sweep();
    Map<String,Object> milk = (Map<String,Object>) forProduct(first, MILK).stream()
        .filter(r -> "ORDERED".equals(((Map<String,Object>) r.get("result")).get("status")) && ((Map<String,Object>) r.get("result")).get("quantity").equals(10))
        .findFirst().orElseThrow(() -> new AssertionError("sweep did not order Sipho's milk: " + first)).get("result");
    assertEquals("SUP-002", milk.get("supplierId"));

    List<Map<String,Object>> second = sweeper.sweep();
    assertTrue(forProduct(second, MILK).stream().noneMatch(r -> ((Map<String,Object>) r.get("result")).get("quantity").equals(10)),
        "an order in progress must not be placed again");
    assertEquals(thandiMilkBefore, inventory.byName("owner-demo", MILK).orElseThrow().getQty(), "other shops are untouched");
  }
}
