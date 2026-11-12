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
}
