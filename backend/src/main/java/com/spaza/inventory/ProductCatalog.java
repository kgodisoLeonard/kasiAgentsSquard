package com.spaza.inventory;

import java.math.BigDecimal;
import java.util.*;

/**
 * DEMO DATA: typical spaza stock lines with a shelf price and wholesale prices from the demo suppliers
 * (SUP-001 Mokoena Meats, SUP-002 Ubuntu Foods Co-op). Barcodes are made up. Used to seed each shop's random
 * stock and as the suppliers' price lists for automatic restocking.
 */
public final class ProductCatalog {
  public record Entry(String name, String category, String barcode, double shelfPrice, Map<String,BigDecimal> wholesale) {
    public double cheapestCost() { return wholesale.values().stream().mapToDouble(BigDecimal::doubleValue).min().orElse(shelfPrice * 0.75); }
  }

  /** Always stocked in every shop: the demo restock rules refer to these. */
  public static final List<String> ESSENTIALS = List.of("White Bread (700g)", "Full Cream Milk (2L)", "Maize Meal (10kg)", "Albany Superior Bread");

  private static final List<Entry> ENTRIES = List.of(
    // Original demo lines (wholesale prices unchanged so existing restock limits still hold)
    e("Minced Meat (kg)", "Meat & Poultry", "600100293841", 90.00, "SUP-001", "62.00"),
    e("White Bread (700g)", "Bakery", "600961823719", 18.00, "SUP-002", "13.00"),
    e("Full Cream Milk (2L)", "Dairy", "600124958102", 32.00, "SUP-002", "24.00"),
    e("Maize Meal (10kg)", "Staples", "600106839201", 105.00, "SUP-002", "80.00"),
    e("Coca-Cola (2L)", "Beverages", "5449000000996", 26.00, "SUP-002", "18.00"),
    e("Ace Maize Meal (2.5kg)", "Staples", "600100100001", 42.00, "SUP-002", "30.00"),
    e("Sunlight Dishwashing Liquid (750ml)", "Household", "600108737001", 30.00, "SUP-002", "19.00"),
    e("Five Roses Tea (100 bags)", "Groceries", "600100111001", 52.00, "SUP-002", "35.00"),
    e("Albany Superior Bread", "Bakery", "600106800001", 18.00, "SUP-002", "12.00"),
    // Meat & poultry (Mokoena; some chicken also from the co-op)
    e("Chicken Pieces (2kg)", "Meat & Poultry", "600100293902", 95.00, "SUP-001", "68.00", "SUP-002", "72.00"),
    e("Boerewors (1kg)", "Meat & Poultry", "600100293919", 99.00, "SUP-001", "70.00"),
    e("Polony (1kg)", "Meat & Poultry", "600100293926", 45.00, "SUP-001", "31.00", "SUP-002", "33.00"),
    e("Russians (6 pack)", "Meat & Poultry", "600100293933", 38.00, "SUP-001", "26.00"),
    e("Eggs (18)", "Dairy", "600124958119", 52.00, "SUP-001", "39.00", "SUP-002", "38.00"),
    // Staples & groceries (co-op)
    e("Sugar (2.5kg)", "Staples", "600106839218", 55.00, "SUP-002", "41.00"),
    e("Rice (2kg)", "Staples", "600106839225", 46.00, "SUP-002", "33.00"),
    e("Cake Flour (2.5kg)", "Staples", "600106839232", 38.00, "SUP-002", "27.00"),
    e("Sunflower Oil (2L)", "Staples", "600106839249", 79.00, "SUP-002", "58.00"),
    e("Samp (2kg)", "Staples", "600106839256", 30.00, "SUP-002", "21.00"),
    e("Baked Beans (410g)", "Groceries", "600100111018", 19.00, "SUP-002", "13.50"),
    e("Pilchards in Tomato (400g)", "Groceries", "600100111025", 28.00, "SUP-002", "20.00"),
    e("Peanut Butter (400g)", "Groceries", "600100111032", 39.00, "SUP-002", "28.00"),
    e("Instant Coffee (100g)", "Groceries", "600100111049", 49.00, "SUP-002", "35.00"),
    e("Soup Powder (400g)", "Groceries", "600100111056", 24.00, "SUP-002", "16.50"),
    e("Chakalaka (410g)", "Groceries", "600100111063", 18.00, "SUP-002", "12.50"),
    e("Atchar (380g)", "Groceries", "600100111070", 27.00, "SUP-002", "19.00"),
    e("2-Minute Noodles (5 pack)", "Groceries", "600100111087", 22.00, "SUP-002", "15.00"),
    e("Maas (2L)", "Dairy", "600124958126", 36.00, "SUP-002", "26.00"),
    e("Brown Bread (700g)", "Bakery", "600961823726", 17.00, "SUP-002", "12.00"),
    e("Vetkoek Mix (1kg)", "Bakery", "600961823733", 25.00, "SUP-002", "17.00"),
    // Beverages & snacks
    e("Orange Juice Blend (1.5L)", "Beverages", "600100500017", 29.00, "SUP-002", "20.00"),
    e("Energy Drink (500ml)", "Beverages", "600100500024", 15.00, "SUP-002", "9.50"),
    e("Still Water (1.5L)", "Beverages", "600100500031", 12.00, "SUP-002", "7.00"),
    e("Potato Chips (125g)", "Snacks", "600100600014", 21.00, "SUP-002", "14.00"),
    e("Maize Snacks (100g)", "Snacks", "600100600021", 9.00, "SUP-002", "5.50"),
    e("Chocolate Bar (80g)", "Snacks", "600100600038", 18.00, "SUP-002", "12.00"),
    e("Lollipops (each)", "Snacks", "600100600045", 2.00, "SUP-002", "1.10"),
    // Household & toiletries
    e("Bath Soap (175g)", "Household", "600108737018", 14.00, "SUP-002", "9.00"),
    e("Washing Powder (1kg)", "Household", "600108737025", 48.00, "SUP-002", "34.00"),
    e("Candles (6 pack)", "Household", "600108737032", 35.00, "SUP-002", "24.00"),
    e("Paraffin (1L)", "Household", "600108737049", 32.00, "SUP-002", "24.00"),
    e("Toilet Paper (4 rolls)", "Household", "600108737056", 29.00, "SUP-002", "20.00"),
    e("Matches (10 boxes)", "Household", "600108737063", 12.00, "SUP-002", "7.50"),
    e("Airtime Voucher R10", "Services", "600199900010", 10.00, "SUP-002", "9.50"));

  public static List<Entry> all() { return ENTRIES; }

  public static Optional<Entry> find(String name) {
    return ENTRIES.stream().filter(x -> x.name().equalsIgnoreCase(name == null ? "" : name.trim())).findFirst();
  }

  private static Entry e(String name, String category, String barcode, double shelf, String... supplierPrices) {
    Map<String,BigDecimal> w = new LinkedHashMap<>();
    for (int i = 0; i < supplierPrices.length; i += 2) w.put(supplierPrices[i], new BigDecimal(supplierPrices[i + 1]));
    return new Entry(name, category, barcode, shelf, Collections.unmodifiableMap(w));
  }

  private ProductCatalog() {}
}
