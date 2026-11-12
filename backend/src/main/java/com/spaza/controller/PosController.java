package com.spaza.controller;

import com.spaza.model.Product;
import com.spaza.model.SaleItem;
import com.spaza.model.SaleRequest;
import com.spaza.repository.InMemoryStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/pos")
public class PosController {

    private final InMemoryStore store;

    public PosController(InMemoryStore store) { this.store = store; }

    @GetMapping("/inventory")
    public Map<String, Object> inventory() {
        return Map.of("success", true, "inventory", store.findAll());
    }

    @GetMapping("/inventory/barcode/{barcode}")
    public ResponseEntity<?> byBarcode(@PathVariable String barcode) {
        return store.findByBarcode(barcode)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of("success", true, "item", p)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Barcode not found")));
    }

    @PostMapping("/inventory")
    public ResponseEntity<?> addProduct(@RequestBody Product p) {
        if (p.getName() == null || p.getBarcode() == null || p.getPrice() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid product"));
        }
        p.setId("prod_" + UUID.randomUUID().toString().substring(0, 8));
        store.save(p);
        return ResponseEntity.status(201).body(Map.of("success", true, "item", p));
    }

    @PostMapping("/sale")
    public ResponseEntity<?> sale(@RequestBody SaleRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cart is empty"));
        }

        double subtotal = 0, totalCost = 0;
        List<Map<String, Object>> lines = new ArrayList<>();

        for (SaleItem si : req.getItems()) {
            Optional<Product> op = store.findById(si.getProductId());
            if (op.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Product not found"));
            }
            Product p = op.get();
            if (p.getQty() < si.getQuantity()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Insufficient stock: " + p.getName()));
            }
            p.setQty(p.getQty() - si.getQuantity());
            subtotal += p.getPrice() * si.getQuantity();
            totalCost += p.getCost() * si.getQuantity();
            lines.add(Map.of(
                "productId", p.getId(),
                "name", p.getName(),
                "qty", si.getQuantity(),
                "unitPrice", p.getPrice(),
                "lineTotal", p.getPrice() * si.getQuantity()
            ));
        }

        double profit = subtotal - totalCost;
        double change = "cash".equalsIgnoreCase(req.getPaymentMethod())
                ? Math.max(0, (req.getCashTendered() != null ? req.getCashTendered() : 0) - subtotal)
                : 0;

        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("saleId", "SALE-" + (100000 + new Random().nextInt(900000)));
        sale.put("timestamp", Instant.now().toString());
        sale.put("items", lines);
        sale.put("subtotal", subtotal);
        sale.put("profit", profit);
        sale.put("paymentMethod", req.getPaymentMethod());
        sale.put("changeDue", change);

        return ResponseEntity.ok(Map.of("success", true, "sale", sale));
    }
}
