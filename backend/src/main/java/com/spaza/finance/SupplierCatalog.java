package com.spaza.finance;

import java.math.BigDecimal;
import java.util.*;

/**
 * Wholesale prices per stock unit (matching the shop inventory names), used for automatic restocking.
 * DEMO DATA: a production build would load each supplier's live price list.
 */
public class SupplierCatalog {
  public record Item(String supplierId, String product, BigDecimal unitPrice) {
    public Map<String,Object> view() { return Map.of("supplierId", supplierId, "product", product, "unitPrice", unitPrice); }
  }

  private static final List<Item> ITEMS = List.of(
    item("SUP-001", "Minced Meat (kg)", "62.00"),
    item("SUP-002", "White Bread (700g)", "13.00"),
    item("SUP-002", "Full Cream Milk (2L)", "24.00"),
    item("SUP-002", "Maize Meal (10kg)", "80.00"),
    item("SUP-002", "Coca-Cola (2L)", "18.00"),
    item("SUP-002", "Ace Maize Meal (2.5kg)", "30.00"),
    item("SUP-002", "Sunlight Dishwashing Liquid (750ml)", "19.00"),
    item("SUP-002", "Five Roses Tea (100 bags)", "35.00"),
    item("SUP-002", "Albany Superior Bread", "12.00"));

  public List<Item> all() { return ITEMS; }

  public Optional<Item> price(String supplierId, String product) {
    return ITEMS.stream().filter(i -> i.supplierId().equals(supplierId) && i.product().equalsIgnoreCase(product)).findFirst();
  }

  private static Item item(String supplierId, String product, String price) { return new Item(supplierId, product, new BigDecimal(price)); }
}
