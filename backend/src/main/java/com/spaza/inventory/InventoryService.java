package com.spaza.inventory;

import com.spaza.model.Product;
import com.spaza.persistence.StateStore;
import com.spaza.persistence.WriteBehind;
import com.spaza.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Each shop's stock, stored in the products table. Every method takes the owner id from the signed-in session,
 * so one owner can never read or change another shop's products (unknown ids simply are "not found").
 *
 * A shop's products are loaded from the database the first time the shop is used and then served from memory;
 * every change is written back on the ordered {@link WriteBehind} thread, so the till never waits for the database.
 * Callers always get copies, so editing a returned product never changes the shop by accident.
 */
@Service
public class InventoryService {
  private final ProductRepository products;
  private final WriteBehind writer;
  private final StateStore state;
  /** owner id -> (product id -> product), for shops loaded so far */
  private final Map<String,Map<String,Product>> shops = new HashMap<>();

  public InventoryService(ProductRepository products, WriteBehind writer, StateStore state) { this.products = products; this.writer = writer; this.state = state; }

  private Map<String,Product> shop(String ownerId) {
    return shops.computeIfAbsent(ownerId, id -> {
      Map<String,Product> m = new LinkedHashMap<>();
      products.findByOwnerIdOrderByNameAsc(id).forEach(p -> m.put(p.getId(), p));
      return m;
    });
  }

  private Product store(Product p) {
    Product snapshot = copy(p);
    writer.submit("products", () -> products.save(snapshot));
    return p;
  }

  public synchronized List<Product> list(String ownerId) {
    return shop(ownerId).values().stream().map(InventoryService::copy)
        .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  public synchronized Optional<Product> find(String ownerId, String productId) {
    return Optional.ofNullable(shop(ownerId).get(productId)).map(InventoryService::copy);
  }

  public synchronized Optional<Product> byBarcode(String ownerId, String barcode) {
    return shop(ownerId).values().stream().filter(p -> Objects.equals(p.getBarcode(), barcode)).findFirst().map(InventoryService::copy);
  }

  /** Exact (case-insensitive) name match first, then a prefix match, e.g. an order for "Maize Meal" finds "Maize Meal (10kg)". */
  public synchronized Optional<Product> byName(String ownerId, String name) {
    String wanted = name == null ? "" : name.trim();
    Optional<Product> exact = shop(ownerId).values().stream().filter(p -> p.getName().equalsIgnoreCase(wanted)).findFirst().map(InventoryService::copy);
    if (exact.isPresent() || wanted.isEmpty()) return exact;
    String w = wanted.toLowerCase(Locale.ROOT);
    return list(ownerId).stream().filter(p -> { String a = p.getName().toLowerCase(Locale.ROOT); return a.startsWith(w) || w.startsWith(a); }).findFirst();
  }

  /** Takes stock off the shelf. Fails without changing anything if there is not enough. */
  public synchronized Product take(String ownerId, String productId, int qty) {
    if (qty < 1) throw new IllegalArgumentException("Quantity must be at least 1");
    Product p = shop(ownerId).get(productId);
    if (p == null) throw new NoSuchElementException("Product not found");
    if (p.getQty() < qty) throw new IllegalStateException("Only " + p.getQty() + " " + p.getName() + " left in stock");
    p.setQty(p.getQty() - qty);
    return copy(store(p));
  }

  public synchronized Optional<Product> putBack(String ownerId, String productId, int qty) {
    Product p = shop(ownerId).get(productId);
    if (p == null) return Optional.empty();
    p.setQty(p.getQty() + qty);
    return Optional.of(copy(store(p)));
  }

  /** Delivered restock goes into the buying shop only. */
  public synchronized Optional<Product> receive(String ownerId, String productName, int qty) {
    return byName(ownerId, productName).map(found -> {
      Product p = shop(ownerId).get(found.getId());
      p.setQty(p.getQty() + qty);
      return copy(store(p));
    });
  }

  public synchronized Optional<Product> setPrice(String ownerId, String productId, double price) {
    Product p = shop(ownerId).get(productId);
    if (p == null) return Optional.empty();
    p.setPrice(price);
    return Optional.of(copy(store(p)));
  }

  public synchronized Product add(String ownerId, Product p) {
    String name = p.getName().trim();
    if (shop(ownerId).values().stream().anyMatch(x -> x.getName().equalsIgnoreCase(name))) throw new IllegalStateException(name + " is already in your stock");
    Product row = copy(p);
    row.setId(newId()); row.setOwnerId(ownerId); row.setName(name);
    shop(ownerId).put(row.getId(), row);
    return copy(store(row));
  }

  /** Deletes a product from this shop's stock list. Returns the deleted product, or empty if it is not in this shop. */
  public synchronized Optional<Product> delete(String ownerId, String productId) {
    Product p = shop(ownerId).remove(productId);
    if (p == null) return Optional.empty();
    writer.submit("products", () -> { if (products.existsById(productId)) products.deleteById(productId); });
    return Optional.of(copy(p));
  }

  static Product copy(Product p) {
    Product c = new Product();
    c.setId(p.getId()); c.setOwnerId(p.getOwnerId()); c.setName(p.getName()); c.setBarcode(p.getBarcode()); c.setCategory(p.getCategory());
    c.setQty(p.getQty()); c.setCost(p.getCost()); c.setPrice(p.getPrice()); c.setLowStock(p.getLowStock());
    return c;
  }

  /**
   * DEMO DATA: every shop stocks the same catalog products, each with its own random quantity, reorder level and a
   * slightly different shelf price. A new shop gets all of them; an existing shop gets any catalog products it is missing,
   * once (recorded in the state store, so a product the owner later deletes is not added back). Random values are seeded
   * per owner and product, so the same shop always gets the same starting shelf.
   */
  // Not @Transactional on purpose: saveAll commits in its own transaction BEFORE the shop's cache is cleared below,
  // so a request arriving during seeding can never cache the shop as empty (the commit used to happen after the clear).
  public synchronized int seedRandomStock(String ownerId) {
    if (state.raw("full-shelf").containsKey(ownerId)) return 0;
    Set<String> have = new HashSet<>();
    products.findByOwnerIdOrderByNameAsc(ownerId).forEach(p -> have.add(p.getName().toLowerCase(Locale.ROOT)));
    List<Product> rows = new ArrayList<>();
    for (ProductCatalog.Entry e : ProductCatalog.all()) {
      if (have.contains(e.name().toLowerCase(Locale.ROOT))) continue;
      Random rnd = new Random((ownerId + "|" + e.name()).hashCode());
      Product p = new Product();
      p.setId(newId()); p.setOwnerId(ownerId); p.setName(e.name()); p.setBarcode(e.barcode()); p.setCategory(e.category());
      int low = 5 + rnd.nextInt(11);
      p.setLowStock(low);
      boolean essential = ProductCatalog.ESSENTIALS.contains(e.name());              // bread, milk, maize start healthy
      p.setQty(!essential && rnd.nextInt(10) < 2 ? rnd.nextInt(low) : low + rnd.nextInt(45));   // ~20% of the rest start low
      double shelf = e.shelfPrice() * (0.95 + rnd.nextDouble() * 0.15);
      p.setPrice(Math.round(shelf * 2) / 2.0);                                     // nearest 50c
      p.setCost(Math.round(e.cheapestCost() * 100) / 100.0);
      rows.add(p);
    }
    if (!rows.isEmpty()) products.saveAll(rows);   // synchronous: the shop's stock exists before anyone uses it
    state.save("full-shelf", ownerId, ProductCatalog.all().size());
    shops.remove(ownerId);                          // the next use loads the rows
    return rows.size();
  }

  private static String newId() { return "prod_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
}
