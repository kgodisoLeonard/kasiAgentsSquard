package com.spaza.controller;

import com.spaza.model.Product;
import com.spaza.model.SaleItem;
import com.spaza.model.SaleRequest;
import com.spaza.repository.InMemoryStore;
import com.spaza.agent.AgentGateway;
import com.spaza.finance.MandateService;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/pos")
@RequireRole("OWNER")
public class PosController {

    private final InMemoryStore store;
    private final List<Map<String, Object>> sales = Collections.synchronizedList(new ArrayList<>());

    private final AgentGateway agent;
    private final MandateService mandates;
    /** Open customer orders at the till (in memory). Stock is taken off the shelf as soon as an item is added. */
    private final Map<String, CustomerOrder> orders = new LinkedHashMap<>();

    private static final class CustomerOrder {
        final String id, ownerId, openedAt; final LinkedHashMap<String, Integer> lines = new LinkedHashMap<>(); String status = "OPEN";
        CustomerOrder(String id, String ownerId) { this.id = id; this.ownerId = ownerId; this.openedAt = Instant.now().toString(); }
    }

    public PosController(InMemoryStore store, AgentGateway agent, MandateService mandates) { this.store = store; this.agent = agent; this.mandates = mandates; }

    // ------------------------------------------------------------------ customer orders (till)

    @GetMapping("/orders/open")
    public synchronized Map<String, Object> openOrder(HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        return orders.values().stream().filter(o -> o.ownerId.equals(u.id()) && "OPEN".equals(o.status)).reduce((a, b) -> b)
            .<Map<String, Object>>map(o -> Map.of("success", true, "order", view(o))).orElseGet(() -> Map.of("success", true));
    }

    @PostMapping("/orders")
    public synchronized Map<String, Object> openNew(HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        CustomerOrder existing = orders.values().stream().filter(o -> o.ownerId.equals(u.id()) && "OPEN".equals(o.status)).reduce((a, b) -> b).orElse(null);
        CustomerOrder o = existing != null ? existing : new CustomerOrder("ORDER-" + (100000 + new Random().nextInt(900000)), u.id());
        orders.put(o.id, o);
        return Map.of("success", true, "order", view(o));
    }

    /** addToOrder: takes the items off the shelf straight away, then lets the agent check the owner's restock rule. */
    @PostMapping("/orders/{id}/items")
    public synchronized Map<String, Object> addToOrder(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        CustomerOrder o = mine(id, u);
        String productId = String.valueOf(body.get("productId"));
        int qty = body.get("quantity") instanceof Number n ? n.intValue() : 1;
        if (qty < 1) throw new IllegalArgumentException("Quantity must be at least 1");
        Product p = store.findById(productId).orElseThrow(() -> new NoSuchElementException("Product not found"));
        if (p.getQty() < qty) throw new IllegalStateException("Only " + p.getQty() + " " + p.getName() + " left in stock");
        p.setQty(p.getQty() - qty);
        o.lines.merge(productId, qty, Integer::sum);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true); r.put("order", view(o)); r.put("item", p);
        restockCheck(u.id(), p).ifPresent(x -> r.put("restock", x));
        return r;
    }

    @PostMapping("/orders/{id}/items/{productId}/remove")
    public synchronized Map<String, Object> removeFromOrder(@PathVariable String id, @PathVariable String productId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        CustomerOrder o = mine(id, AuthInterceptor.current(req));
        int inOrder = o.lines.getOrDefault(productId, 0);
        if (inOrder == 0) throw new NoSuchElementException("That item is not in this order");
        int qty = Math.min(inOrder, body != null && body.get("quantity") instanceof Number n ? Math.max(1, n.intValue()) : 1);
        store.findById(productId).ifPresent(p -> p.setQty(p.getQty() + qty));
        if (inOrder - qty == 0) o.lines.remove(productId); else o.lines.put(productId, inOrder - qty);
        return Map.of("success", true, "order", view(o));
    }

    @PostMapping("/orders/{id}/cancel")
    public synchronized Map<String, Object> cancel(@PathVariable String id, HttpServletRequest req) {
        CustomerOrder o = mine(id, AuthInterceptor.current(req));
        o.lines.forEach((productId, qty) -> store.findById(productId).ifPresent(p -> p.setQty(p.getQty() + qty)));
        o.lines.clear(); o.status = "CANCELLED";
        return Map.of("success", true, "order", view(o));
    }

    /** Closes the order: payment is recorded as a sale. Stock was already taken off when items were added. */
    @PostMapping("/orders/{id}/close")
    public synchronized ResponseEntity<?> close(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        CustomerOrder o = mine(id, AuthInterceptor.current(req));
        if (o.lines.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Order is empty"));
        Map<String, Object> v = view(o);
        double subtotal = ((Number) v.get("subtotal")).doubleValue();
        String method = String.valueOf(body.getOrDefault("paymentMethod", "cash"));
        double tendered = body.get("cashTendered") instanceof Number n ? n.doubleValue() : 0;
        if ("cash".equalsIgnoreCase(method) && tendered < subtotal) return ResponseEntity.badRequest().body(Map.of("error", "Cash tendered is less than the total"));
        o.status = "CLOSED";
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("saleId", o.id); sale.put("timestamp", Instant.now().toString()); sale.put("items", v.get("lines"));
        sale.put("subtotal", subtotal); sale.put("profit", v.get("profit")); sale.put("paymentMethod", method);
        sale.put("changeDue", "cash".equalsIgnoreCase(method) ? Math.max(0, tendered - subtotal) : 0.0);
        sales.add(sale);
        return ResponseEntity.ok(Map.of("success", true, "sale", sale, "order", view(o)));
    }

    private CustomerOrder mine(String id, AuthUser u) {
        CustomerOrder o = orders.get(id);
        if (o == null || !o.ownerId.equals(u.id())) throw new NoSuchElementException("Order not found");
        if (!"OPEN".equals(o.status)) throw new IllegalStateException("Order is already " + o.status.toLowerCase(Locale.ROOT));
        return o;
    }

    private Map<String, Object> view(CustomerOrder o) {
        List<Map<String, Object>> lines = new ArrayList<>(); double subtotal = 0, profit = 0;
        for (var e : o.lines.entrySet()) {
            Product p = store.findById(e.getKey()).orElse(null);
            if (p == null) continue;
            int q = e.getValue();
            lines.add(Map.of("productId", p.getId(), "name", p.getName(), "category", String.valueOf(p.getCategory()), "qty", q, "unitPrice", p.getPrice(), "lineTotal", p.getPrice() * q));
            subtotal += p.getPrice() * q; profit += (p.getPrice() - p.getCost()) * q;
        }
        return Map.of("id", o.id, "status", o.status, "openedAt", o.openedAt, "lines", lines, "subtotal", subtotal, "profit", profit);
    }

    /** Calls the agent only when the owner has a restock rule for this product and stock has dropped below its level. */
    private Optional<Map<String, Object>> restockCheck(String ownerId, Product p) {
        return mandates.restockRules(ownerId).stream().filter(r -> r.product().equalsIgnoreCase(p.getName()) && p.getQty() < r.reorderBelow()).findFirst()
            .map(r -> agent.execute("PLACE_RESTOCK_ORDER", Map.of("product", p.getName()), ownerId));
    }

    @GetMapping("/inventory")
    public Map<String, Object> inventory() {
        return Map.of("success", true, "inventory", store.findAll());
    }

    @GetMapping("/sales")
    public Map<String, Object> salesHistory() {
        double revenue = 0, profit = 0;
        synchronized (sales) {
            for (Map<String, Object> s : sales) {
                revenue += (Double) s.get("subtotal");
                profit += (Double) s.get("profit");
            }
            List<Map<String, Object>> recent = new ArrayList<>(sales);
            Collections.reverse(recent);
            return Map.of("success", true, "count", sales.size(), "revenue", revenue, "profit", profit, "sales", recent);
        }
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

    @PutMapping("/inventory/{id}/price")
    public ResponseEntity<?> updatePrice(@PathVariable String id, @RequestBody Map<String, Object> body) {
        double price = ((Number) body.getOrDefault("price", 0)).doubleValue();
        if (price <= 0) return ResponseEntity.badRequest().body(Map.of("error", "Price must be greater than zero"));
        return store.updatePrice(id, price)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of("success", true, "item", p)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Product not found")));
    }

    @PostMapping("/sale")
    public synchronized ResponseEntity<?> sale(@RequestBody SaleRequest req, HttpServletRequest httpReq) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cart is empty"));
        }

        // Validate everything first so a failed sale never partially deducts stock
        for (SaleItem si : req.getItems()) {
            Optional<Product> op = store.findById(si.getProductId());
            if (op.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Product not found"));
            }
            if (si.getQuantity() <= 0 || op.get().getQty() < si.getQuantity()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Insufficient stock: " + op.get().getName()));
            }
        }

        double subtotal = 0, totalCost = 0;
        List<Map<String, Object>> lines = new ArrayList<>();

        for (SaleItem si : req.getItems()) {
            Product p = store.findById(si.getProductId()).orElseThrow();
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
        sales.add(sale);

        List<Map<String, Object>> restock = new ArrayList<>();
        String ownerId = AuthInterceptor.current(httpReq).id();
        for (SaleItem si : req.getItems()) store.findById(si.getProductId()).flatMap(p -> restockCheck(ownerId, p)).ifPresent(restock::add);
        return ResponseEntity.ok(Map.of("success", true, "sale", sale, "restock", restock));
    }

    @PostMapping("/restock-check")
    public synchronized Map<String, Object> restockCheckAll(HttpServletRequest req) {
        String ownerId = AuthInterceptor.current(req).id();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Product p : store.findAll()) restockCheck(ownerId, p).ifPresent(results::add);
        return Map.of("success", true, "results", results);
    }
}
