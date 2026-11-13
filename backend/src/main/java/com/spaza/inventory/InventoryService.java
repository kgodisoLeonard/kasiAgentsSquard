package com.spaza.inventory;

import com.spaza.model.Product;
import com.spaza.persistence.WriteBehind;
import com.spaza.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
  /** owner id -> (product id -> product), for shops loaded so far */
  private final Map<String,Map<String,Product>> shops = new HashMap<>();

  public InventoryService(ProductRepository products, WriteBehind writer) { this.products = products; this.writer = writer; }

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
   * DEMO DATA: gives a shop a random selection of catalog products with random stock levels and slightly varied
   * shelf prices. The essentials (bread, milk, maize meal) are always included. Does nothing if the shop has stock.
   * Seeded per owner id, so the same owner always gets the same starting shelf.
   */
  @Transactional
  public synchronized int seedRandomStock(String ownerId) {
    if ((shops.containsKey(ownerId) && !shops.get(ownerId).isEmpty()) || products.existsByOwnerId(ownerId)) return 0;
    Random rnd = new Random(ownerId.hashCode());
    List<ProductCatalog.Entry> pool = new ArrayList<>(ProductCatalog.all());
    Collections.shuffle(pool, rnd);
    int extra = 22 + rnd.nextInt(10);
    List<ProductCatalog.Entry> chosen = new ArrayList<>();
    for (ProductCatalog.Entry e : ProductCatalog.all()) if (ProductCatalog.ESSENTIALS.contains(e.name())) chosen.add(e);
    for (ProductCatalog.Entry e : pool) { if (chosen.size() >= ProductCatalog.ESSENTIALS.size() + extra) break; if (!chosen.contains(e)) chosen.add(e); }
    List<Product> rows = new ArrayList<>();
    for (ProductCatalog.Entry e : chosen) {
      Product p = new Product();
      p.setId(newId()); p.setOwnerId(ownerId); p.setName(e.name()); p.setBarcode(e.barcode()); p.setCategory(e.category());
      int low = 5 + rnd.nextInt(11);
      p.setLowStock(low);
      p.setQty(rnd.nextInt(10) < 2 ? rnd.nextInt(low) : low + rnd.nextInt(45));   // ~20% start below their reorder level
      double shelf = e.shelfPrice() * (0.95 + rnd.nextDouble() * 0.15);
      p.setPrice(Math.round(shelf * 2) / 2.0);                                     // nearest 50c
      p.setCost(Math.round(e.cheapestCost() * 100) / 100.0);
      rows.add(p);
    }
    products.saveAll(rows);   // seeding is synchronous: the shop's stock exists before anyone uses it
    shops.remove(ownerId);    // the next use loads the seeded rows
    return rows.size();
  }

  private static String newId() { return "prod_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
}
