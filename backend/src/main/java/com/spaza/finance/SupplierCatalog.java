package com.spaza.finance;

import com.spaza.inventory.ProductCatalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Predicate;

/**
 * Suppliers' price lists: wholesale price and available stock per product. Automatic restocking and group orders buy
 * at these prices; an order bigger than a supplier's stock is redirected to another verified supplier that has enough.
 * In the app the lists live in the supplier_prices table (seeded once from the demo prices in {@link ProductCatalog})
 * and each supplier maintains its own; reads are served from memory. Unit tests use the demo prices in memory only.
 */
public class SupplierCatalog {
  /** stock == null means the supplier does not track stock for this product (treated as always available). */
  public record Item(String supplierId, String product, BigDecimal unitPrice, Integer stock) {
    public Item(String supplierId, String product, BigDecimal unitPrice) { this(supplierId, product, unitPrice, null); }
    public boolean hasStock(int qty) { return stock == null || stock >= qty; }
    public Map<String,Object> view() {
      Map<String,Object> v = new LinkedHashMap<>();
      v.put("supplierId", supplierId); v.put("product", product); v.put("unitPrice", unitPrice); v.put("stock", stock);
      return v;
    }
  }

  /** Where price lists are kept. */
  public interface Store {
    List<Item> load();
    void save(Item item);
    default void delete(Item item) {}
  }

  static final List<Item> DEMO_PRICES = demoPrices();

  private static List<Item> demoPrices() {
    List<Item> list = new ArrayList<>(ProductCatalog.all().stream()
        .flatMap(e -> e.wholesale().entrySet().stream().map(w -> new Item(w.getKey(), e.name(), w.getValue(), demoStock(w.getKey(), e.name())))).toList());
    for (ProductCatalog.DemoSupplier s : ProductCatalog.DEMO_SUPPLIERS)
      ProductCatalog.demoSupplierPrices(s).forEach((product, price) -> list.add(new Item(s.id(), product, price, demoStock(s.id(), product))));
    return List.copyOf(list);
  }

  /** DEMO stock: 40-400 units, fixed per supplier and product. */
  static int demoStock(String supplierId, String product) { return 40 + Math.floorMod((supplierId + "|" + product).hashCode(), 361); }

  private final Store store;
  private final List<Item> items = new ArrayList<>();

  /** Demo prices in memory (unit tests). Stock is not tracked here, so unit tests are unaffected by quantities. */
  public SupplierCatalog() {
    this.store = null;
    DEMO_PRICES.forEach(i -> items.add(new Item(i.supplierId(), i.product(), i.unitPrice(), null)));
  }

  /** Database-backed. Missing demo lines are added, and lines without a stock figure get demo stock once. */
  public SupplierCatalog(Store store) {
    this.store = store;
    List<Item> loaded = new ArrayList<>(store.load());
    for (Item d : DEMO_PRICES) {
      Optional<Item> have = loaded.stream().filter(i -> i.supplierId().equals(d.supplierId()) && i.product().equalsIgnoreCase(d.product())).findFirst();
      if (have.isEmpty()) { store.save(d); loaded.add(d); }
      else if (have.get().stock() == null) {
        Item withStock = new Item(have.get().supplierId(), have.get().product(), have.get().unitPrice(), d.stock());
        store.save(withStock); loaded.set(loaded.indexOf(have.get()), withStock);
      }
    }
    items.addAll(loaded);
  }

  public synchronized List<Item> all() { return List.copyOf(items); }

  public synchronized List<Item> bySupplier(String supplierId) {
    return items.stream().filter(i -> i.supplierId().equals(supplierId)).sorted(Comparator.comparing(Item::product, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  public synchronized Optional<Item> price(String supplierId, String product) {
    return items.stream().filter(i -> i.supplierId().equals(supplierId) && i.product().equalsIgnoreCase(product)).findFirst();
  }

  /** True when the supplier lists the product and has at least qty in stock (or does not track stock). */
  public synchronized boolean hasStock(String supplierId, String product, int qty) {
    return price(supplierId, product).map(i -> i.hasStock(qty)).orElse(false);
  }

  /** Cheapest other supplier that sells the product, has enough stock and passes the check (e.g. verified). */
  public synchronized Optional<Item> alternative(String product, int qty, Set<String> excludeSuppliers, Predicate<Item> acceptable) {
    return items.stream().filter(i -> i.product().equalsIgnoreCase(product) && !excludeSuppliers.contains(i.supplierId()) && i.hasStock(qty) && acceptable.test(i))
        .min(Comparator.comparing(Item::unitPrice));
  }

  /** Takes qty out of the supplier's available stock (for an order). No-op when stock is not tracked. */
  public synchronized void reserve(String supplierId, String product, int qty) { adjust(supplierId, product, -qty); }

  /** Gives qty back (order cancelled, refunded, or partly unfunded). */
  public synchronized void release(String supplierId, String product, int qty) { adjust(supplierId, product, qty); }

  private void adjust(String supplierId, String product, int delta) {
    price(supplierId, product).filter(i -> i.stock() != null).ifPresent(i -> {
      Item next = new Item(i.supplierId(), i.product(), i.unitPrice(), Math.max(0, i.stock() + delta));
      items.set(items.indexOf(i), next);
      if (store != null) store.save(next);
    });
  }

  /** Adds or changes one line of a supplier's own price list. Returns the previous price, if any. Stock unchanged. */
  public synchronized Optional<BigDecimal> setPrice(String supplierId, String product, BigDecimal unitPrice) {
    return setItem(supplierId, product, unitPrice, price(supplierId, product == null ? "" : product.trim()).map(Item::stock).orElse(null));
  }

  /** Adds or changes one line (price and stock). Returns the previous price, if any. */
  public synchronized Optional<BigDecimal> setItem(String supplierId, String product, BigDecimal unitPrice, Integer stock) {
    String name = product == null ? "" : product.trim();
    if (name.isEmpty() || name.length() > 120) throw new IllegalArgumentException("Product name is required (up to 120 characters)");
    if (unitPrice == null || unitPrice.signum() <= 0) throw new IllegalArgumentException("Price must be greater than zero");
    if (unitPrice.compareTo(new BigDecimal("100000")) > 0) throw new IllegalArgumentException("Price is too high");
    if (stock != null && (stock < 0 || stock > 1_000_000)) throw new IllegalArgumentException("Stock must be between 0 and 1,000,000");
    BigDecimal money = unitPrice.setScale(2, RoundingMode.HALF_UP);
    Optional<Item> before = price(supplierId, name);
    Item next = new Item(supplierId, before.map(Item::product).orElse(name), money, stock);
    before.ifPresent(items::remove);
    items.add(next);
    if (store != null) store.save(next);
    return before.map(Item::unitPrice);
  }

  /** Removes a product from a supplier's list. Returns the removed line, or empty if it was not listed. */
  public synchronized Optional<Item> remove(String supplierId, String product) {
    Optional<Item> found = price(supplierId, product == null ? "" : product.trim());
    found.ifPresent(i -> { items.remove(i); if (store != null) store.delete(i); });
    return found;
  }
}
