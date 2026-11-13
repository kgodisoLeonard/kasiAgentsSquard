package com.spaza.finance;

import com.spaza.inventory.ProductCatalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Suppliers' price lists: the wholesale price per stock unit, matching the shop inventory names. Automatic restocking
 * orders at these prices. In the app the lists live in the supplier_prices table (seeded once from the demo prices in
 * {@link ProductCatalog}) and each supplier maintains its own; reads are served from memory. Unit tests use the demo
 * prices in memory only.
 */
public class SupplierCatalog {
  public record Item(String supplierId, String product, BigDecimal unitPrice) {
    public Map<String,Object> view() { return Map.of("supplierId", supplierId, "product", product, "unitPrice", unitPrice); }
  }

  /** Where price lists are kept. */
  public interface Store {
    List<Item> load();
    void save(Item item);
  }

  static final List<Item> DEMO_PRICES = ProductCatalog.all().stream()
      .flatMap(e -> e.wholesale().entrySet().stream().map(w -> new Item(w.getKey(), e.name(), w.getValue()))).toList();

  private final Store store;
  private final List<Item> items = new ArrayList<>();

  /** Demo prices in memory (unit tests). */
  public SupplierCatalog() { this(null); }

  /** Database-backed. An empty table is seeded once with the demo prices. */
  public SupplierCatalog(Store store) {
    this.store = store;
    List<Item> loaded = store == null ? List.of() : store.load();
    if (store != null && loaded.isEmpty()) { DEMO_PRICES.forEach(store::save); loaded = DEMO_PRICES; }
    items.addAll(store == null ? DEMO_PRICES : loaded);
  }

  public synchronized List<Item> all() { return List.copyOf(items); }

  public synchronized List<Item> bySupplier(String supplierId) {
    return items.stream().filter(i -> i.supplierId().equals(supplierId)).sorted(Comparator.comparing(Item::product, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  public synchronized Optional<Item> price(String supplierId, String product) {
    return items.stream().filter(i -> i.supplierId().equals(supplierId) && i.product().equalsIgnoreCase(product)).findFirst();
  }

  /** Adds or changes one line of a supplier's own price list. Returns the previous price, if any. */
  public synchronized Optional<BigDecimal> setPrice(String supplierId, String product, BigDecimal unitPrice) {
    String name = product == null ? "" : product.trim();
    if (name.isEmpty() || name.length() > 120) throw new IllegalArgumentException("Product name is required (up to 120 characters)");
    if (unitPrice == null || unitPrice.signum() <= 0) throw new IllegalArgumentException("Price must be greater than zero");
    if (unitPrice.compareTo(new BigDecimal("100000")) > 0) throw new IllegalArgumentException("Price is too high");
    BigDecimal money = unitPrice.setScale(2, RoundingMode.HALF_UP);
    Optional<Item> before = price(supplierId, name);
    Item next = new Item(supplierId, before.map(Item::product).orElse(name), money);
    before.ifPresent(items::remove);
    items.add(next);
    if (store != null) store.save(next);
    return before.map(Item::unitPrice);
  }
}
