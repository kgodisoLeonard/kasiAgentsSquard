package com.spaza.finance;

import com.spaza.inventory.ProductCatalog;

import java.math.BigDecimal;
import java.util.*;

/**
 * Wholesale prices per stock unit (matching the shop inventory names), used for automatic restocking.
 * DEMO DATA from {@link ProductCatalog}: a production build would load each supplier's live price list.
 */
public class SupplierCatalog {
  public record Item(String supplierId, String product, BigDecimal unitPrice) {
    public Map<String,Object> view() { return Map.of("supplierId", supplierId, "product", product, "unitPrice", unitPrice); }
  }

  private static final List<Item> ITEMS = ProductCatalog.all().stream()
      .flatMap(e -> e.wholesale().entrySet().stream().map(w -> new Item(w.getKey(), e.name(), w.getValue()))).toList();

  public List<Item> all() { return ITEMS; }

  public List<Item> bySupplier(String supplierId) { return ITEMS.stream().filter(i -> i.supplierId().equals(supplierId)).toList(); }

  public Optional<Item> price(String supplierId, String product) {
    return ITEMS.stream().filter(i -> i.supplierId().equals(supplierId) && i.product().equalsIgnoreCase(product)).findFirst();
  }
}
