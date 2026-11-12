package com.spaza.repository;

import com.spaza.model.Product;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InMemoryStore {

    private final List<Product> products = new ArrayList<>();

    public InMemoryStore() {
        products.add(product("Minced Meat (kg)", "600100293841", "Meat & Poultry", 45, 65.0, 90.0, 10));
        products.add(product("White Bread (700g)", "600961823719", "Bakery", 12, 14.0, 18.0, 15));
        products.add(product("Full Cream Milk (2L)", "600124958102", "Dairy", 28, 26.0, 32.0, 10));
        products.add(product("Maize Meal (10kg)", "600106839201", "Staples", 8, 85.0, 105.0, 10));
        products.add(product("Coca-Cola (2L)", "5449000000996", "Beverages", 40, 20.0, 26.0, 12));
        products.add(product("Ace Maize Meal (2.5kg)", "600100100001", "Staples", 9, 32.0, 42.0, 10));
        products.add(product("Sunlight Dishwashing Liquid (750ml)", "600108737001", "Household", 6, 21.0, 30.0, 10));
        products.add(product("Five Roses Tea (100 bags)", "600100111001", "Groceries", 4, 38.0, 52.0, 10));
        products.add(product("Albany Superior Bread", "600106800001", "Bakery", 14, 13.0, 18.0, 10));
    }

    private Product product(String name, String barcode, String cat, int qty, double cost, double price, int low) {
        Product p = new Product();
        p.setId("prod_" + UUID.randomUUID().toString().substring(0, 8));
        p.setName(name);
        p.setBarcode(barcode);
        p.setCategory(cat);
        p.setQty(qty);
        p.setCost(cost);
        p.setPrice(price);
        p.setLowStock(low);
        return p;
    }

    public List<Product> findAll() { return products; }

    public Optional<Product> findById(String id) {
        return products.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    public Optional<Product> findByBarcode(String barcode) {
        return products.stream().filter(p -> p.getBarcode().equals(barcode)).findFirst();
    }

    public Product save(Product product) {
        products.add(product);
        return product;
    }

    public Optional<Product> findByName(String name) {
        String wanted = name == null ? "" : name.trim().toLowerCase();
        return products.stream().filter(p -> {
            String actual = p.getName().toLowerCase();
            return actual.equals(wanted) || actual.startsWith(wanted) || wanted.startsWith(actual);
        }).findFirst();
    }

    public Optional<Product> updatePrice(String id, double price) {
        return findById(id).map(product -> { product.setPrice(price); return product; });
    }
}
