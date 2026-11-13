package com.spaza.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.spaza.agent.AgentGateway;
import com.spaza.agent.LlmAgent;
import com.spaza.finance.GroupOrder;
import com.spaza.finance.GroupOrderService;
import com.spaza.finance.MandateService;
import com.spaza.inventory.InventoryService;
import com.spaza.model.*;
import com.spaza.persistence.StateStore;
import com.spaza.persistence.WriteBehind;
import com.spaza.repository.SaleRepository;
import com.spaza.repository.TillOrderRepository;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The till. Owners only, and every call is scoped to the signed-in owner's shop: stock, customer orders and sales are
 * stored per shop in the database. Taking an item off the shelf lets the agent check the owner's restock rule.
 */
@RestController
@RequestMapping("/api/pos")
@RequireRole("OWNER")
public class PosController {
    private static final TypeReference<LinkedHashMap<String, Integer>> LINES = new TypeReference<>() {};

    private final InventoryService inventory;
    private final TillOrderRepository tillOrders;
    private final SaleRepository sales;
    private final AgentGateway agent;
    private final MandateService mandates;
    private final GroupOrderService groupOrders;
    private final LlmAgent llm;
    private final WriteBehind writer;
    private final com.spaza.repository.AgentPolicy humanRules;
    /** Each shop's open customer order and sales, loaded from the database on first use; writes go to the writer thread. */
    private final Map<String, Optional<TillOrder>> openOrders = new HashMap<>();
    private final Map<String, List<Sale>> salesByOwner = new HashMap<>();
    /** LLM restock decisions run here so the till never waits for the model (which can take many seconds). */
    private final ExecutorService agentWork = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "agent-restock"); t.setDaemon(true); return t; });
    private final Set<String> agentBusy = Collections.synchronizedSet(new HashSet<>());

    public PosController(InventoryService inventory, TillOrderRepository tillOrders, SaleRepository sales, AgentGateway agent, MandateService mandates,
                         GroupOrderService groupOrders, LlmAgent llm, WriteBehind writer, com.spaza.repository.AgentPolicy humanRules) {
        this.humanRules = humanRules;
        this.inventory = inventory; this.tillOrders = tillOrders; this.sales = sales; this.agent = agent; this.mandates = mandates;
        this.groupOrders = groupOrders; this.llm = llm; this.writer = writer;
    }

    private synchronized Optional<TillOrder> open(String ownerId) {
        return openOrders.computeIfAbsent(ownerId, id -> tillOrders.findFirstByOwnerIdAndStatusOrderByOpenedAtDesc(id, "OPEN"));
    }

    private synchronized void saveOrder(TillOrder o) {
        openOrders.put(o.getOwnerId(), "OPEN".equals(o.getStatus()) ? Optional.of(o) : Optional.empty());
        TillOrder snapshot = o.copy();
        writer.submit("till_orders", () -> tillOrders.save(snapshot));
    }

    private synchronized List<Sale> salesOf(String ownerId) {
        return salesByOwner.computeIfAbsent(ownerId, id -> new ArrayList<>(sales.findByOwnerIdOrderByCreatedAtDesc(id)));
    }

    private synchronized Sale recordSale(Sale s) {
        salesOf(s.getOwnerId()).add(0, s);
        writer.submit("sales", () -> sales.save(s));
        return s;
    }

    // ------------------------------------------------------------------ customer orders (till)

    @GetMapping("/orders/open")
    public synchronized Map<String, Object> openOrder(HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        return open(u.id()).<Map<String, Object>>map(o -> Map.of("success", true, "order", view(o))).orElseGet(() -> Map.of("success", true));
    }

    @PostMapping("/orders")
    public synchronized Map<String, Object> openNew(HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        TillOrder o = open(u.id()).orElse(null);
        if (o == null) { o = new TillOrder("ORDER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), u.id()); saveOrder(o); }
        return Map.of("success", true, "order", view(o));
    }

    /** Takes the items off the shelf straight away, then lets the agent check the owner's restock rule. */
    @PostMapping("/orders/{id}/items")
    public Map<String, Object> addToOrder(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        Map<String, Object> r = new LinkedHashMap<>();
        Product p;
        synchronized (this) {
            TillOrder o = mine(id, u);
            String productId = String.valueOf(body.get("productId"));
            int qty = body.get("quantity") instanceof Number n ? n.intValue() : 1;
            p = inventory.take(u.id(), productId, qty);
            LinkedHashMap<String, Integer> lines = lines(o);
            lines.merge(productId, qty, Integer::sum);
            o.setLinesJson(StateStore.write(lines));
            saveOrder(o);
            r.put("success", true); r.put("order", view(o)); r.put("item", p);
        }
        restockCheck(u.id(), p).ifPresent(x -> r.put("restock", x));
        return r;
    }

    @PostMapping("/orders/{id}/items/{productId}/remove")
    public synchronized Map<String, Object> removeFromOrder(@PathVariable String id, @PathVariable String productId, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        TillOrder o = mine(id, u);
        LinkedHashMap<String, Integer> lines = lines(o);
        int inOrder = lines.getOrDefault(productId, 0);
        if (inOrder == 0) throw new NoSuchElementException("That item is not in this order");
        int qty = Math.min(inOrder, body != null && body.get("quantity") instanceof Number n ? Math.max(1, n.intValue()) : 1);
        inventory.putBack(u.id(), productId, qty);
        if (inOrder - qty == 0) lines.remove(productId); else lines.put(productId, inOrder - qty);
        o.setLinesJson(StateStore.write(lines));
        saveOrder(o);
        return Map.of("success", true, "order", view(o));
    }

    @PostMapping("/orders/{id}/cancel")
    public synchronized Map<String, Object> cancel(@PathVariable String id, HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        TillOrder o = mine(id, u);
        lines(o).forEach((productId, qty) -> inventory.putBack(u.id(), productId, qty));
        o.setLinesJson("{}"); o.setStatus("CANCELLED");
        saveOrder(o);
        return Map.of("success", true, "order", view(o));
    }

    /** Closes the order: payment is recorded as a sale. Stock was already taken off when items were added. */
    @PostMapping("/orders/{id}/close")
    public synchronized ResponseEntity<?> close(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        AuthUser u = AuthInterceptor.current(req);
        TillOrder o = mine(id, u);
        if (lines(o).isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Order is empty"));
        Map<String, Object> v = view(o);
        double subtotal = ((Number) v.get("subtotal")).doubleValue();
        String method = String.valueOf(body.getOrDefault("paymentMethod", "cash"));
        double tendered = body.get("cashTendered") instanceof Number n ? n.doubleValue() : 0;
        if ("cash".equalsIgnoreCase(method) && tendered < subtotal) return ResponseEntity.badRequest().body(Map.of("error", "Cash tendered is less than the total"));
        o.setStatus("CLOSED");
        saveOrder(o);
        Sale sale = recordSale(new Sale(o.getId(), u.id(), StateStore.write(v.get("lines")), subtotal, ((Number) v.get("profit")).doubleValue(), method,
            "cash".equalsIgnoreCase(method) ? Math.max(0, tendered - subtotal) : 0.0));
        return ResponseEntity.ok(Map.of("success", true, "sale", saleView(sale), "order", view(o)));
    }

    private TillOrder mine(String id, AuthUser u) {
        // Only the shop's current open order can be changed; anything else (another shop's, or a closed one) is not found.
        return open(u.id()).filter(o -> o.getId().equals(id)).orElseThrow(() -> new NoSuchElementException("Order not found or already closed"));
    }

    private static LinkedHashMap<String, Integer> lines(TillOrder o) {
        try { return StateStore.JSON.readValue(o.getLinesJson(), LINES); } catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private Map<String, Object> view(TillOrder o) {
        List<Map<String, Object>> lines = new ArrayList<>(); double subtotal = 0, profit = 0;
        for (var e : lines(o).entrySet()) {
            Product p = inventory.find(o.getOwnerId(), e.getKey()).orElse(null);
            if (p == null) continue;
            int q = e.getValue();
            lines.add(Map.of("productId", p.getId(), "name", p.getName(), "category", String.valueOf(p.getCategory()), "qty", q, "unitPrice", p.getPrice(), "lineTotal", p.getPrice() * q));
            subtotal += p.getPrice() * q; profit += (p.getPrice() - p.getCost()) * q;
        }
        return Map.of("id", o.getId(), "status", o.getStatus(), "openedAt", o.getOpenedAt().toString(), "lines", lines, "subtotal", subtotal, "profit", profit);
    }

    // ------------------------------------------------------------------ restock

    /**
     * Calls the agent only when the owner has a restock rule for this product and stock has dropped below its level.
     * Rule-only agent: decides instantly, so the result is returned with the sale. LLM agent: the model can take many
     * seconds, so the decision runs in the background and the till gets AGENT_WORKING straight away; the order then
     * shows as "on order" in the stock list and the agent notifies the owner.
     */
    private Optional<Map<String, Object>> restockCheck(String ownerId, Product p) {
        Optional<MandateService.RestockRule> rule = mandates.restockRules(ownerId).stream().filter(r -> r.product().equalsIgnoreCase(p.getName()) && p.getQty() < r.reorderBelow()).findFirst();
        if (rule.isEmpty()) return Optional.empty();
        // Rule-only agent, or Human Rules forbid ordering at this level: the guarded action answers instantly.
        if (!llm.configured() || humanRules.rules(ownerId).blocks(p.getQty()) != null)
            return Optional.of(agent.execute("PLACE_RESTOCK_ORDER", Map.of("product", p.getName()), ownerId));
        String key = ownerId + "|" + p.getName().toLowerCase(Locale.ROOT);
        if (agentBusy.add(key)) agentWork.submit(() -> {
            try { llmRestock(ownerId, p, rule.get()); }
            catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(PosController.class).warn("Background restock failed for {}: {}", p.getName(), e.getMessage()); }
            finally { agentBusy.remove(key); }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", p.getName()); result.put("stock", p.getQty()); result.put("reorderBelow", rule.get().reorderBelow());
        result.put("status", "AGENT_WORKING"); result.put("reason", "The agent is reviewing this restock in the background under your rule.");
        return Optional.of(Map.of("allowed", true, "action", "PLACE_RESTOCK_ORDER", "result", result));
    }

    /** The LLM decides and acts through the same guarded tools. If it fails or does not act, the owner's rule still applies. */
    private Map<String, Object> llmRestock(String ownerId, Product p, MandateService.RestockRule rule) {
        Map<String, Object> run;
        try {
            run = llm.run(ownerId, "A till sale just took '" + p.getName() + "' down to " + p.getQty() + " in stock, below the owner's restock level of "
                + rule.reorderBelow() + ". Check the situation and restock according to the owner's mandate.", "till");
        } catch (RuntimeException e) {
            run = Map.of("error", String.valueOf(e.getMessage()), "steps", List.of());
        }
        Map<String, Object> placed = null;
        if (run.get("steps") instanceof List<?> steps) for (Object o : steps) if (o instanceof Map<?, ?> step && "PLACE_RESTOCK_ORDER".equals(String.valueOf(step.get("tool")).toUpperCase(Locale.ROOT))) {
            @SuppressWarnings("unchecked") Map<String, Object> res = (Map<String, Object>) step.get("result");
            placed = res;
        }
        boolean fallback = placed == null;
        Map<String, Object> result = new LinkedHashMap<>(fallback ? agent.execute("PLACE_RESTOCK_ORDER", Map.of("product", p.getName()), ownerId) : placed);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("model", run.getOrDefault("model", "")); info.put("answer", run.get("answer")); info.put("error", run.get("error"));
        info.put("tools", run.get("steps") instanceof List<?> l ? l.stream().map(x -> ((Map<?, ?>) x).get("tool")).toList() : List.of());
        info.put("fallbackToRule", fallback);
        result.put("llm", info);
        return result;
    }

    @PostMapping("/restock-check")
    public Map<String, Object> restockCheckAll(HttpServletRequest req) {
        String ownerId = AuthInterceptor.current(req).id();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Product p : inventory.list(ownerId)) restockCheck(ownerId, p).ifPresent(results::add);
        return Map.of("success", true, "results", results);
    }

    // ------------------------------------------------------------------ stock

    /** The shop's stock. Products with a restock order in progress carry onOrder, so the owner can see why the agent is waiting. */
    @GetMapping("/inventory")
    public Map<String, Object> inventory(HttpServletRequest req) {
        String ownerId = AuthInterceptor.current(req).id();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Product p : inventory.list(ownerId)) rows.add(productView(ownerId, p));
        return Map.of("success", true, "inventory", rows);
    }

    private Map<String, Object> productView(String ownerId, Product p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", p.getId()); v.put("name", p.getName()); v.put("barcode", p.getBarcode()); v.put("category", p.getCategory());
        v.put("qty", p.getQty()); v.put("cost", p.getCost()); v.put("price", p.getPrice()); v.put("lowStock", p.getLowStock());
        Optional<GroupOrder> open = groupOrders.openOrderForMember(ownerId, p.getName());
        v.put("onOrder", open.<Object>map(o -> Map.of("orderId", o.id(), "status", o.status().name(), "supplierId", o.supplierId(),
            "quantity", o.share(ownerId).map(GroupOrder.Share::quantity).orElse(0))).orElse(null));
        return v;
    }

    @GetMapping("/sales")
    public Map<String, Object> salesHistory(HttpServletRequest req) {
        List<Sale> mine = List.copyOf(salesOf(AuthInterceptor.current(req).id()));
        double revenue = mine.stream().mapToDouble(Sale::getSubtotal).sum(), profit = mine.stream().mapToDouble(Sale::getProfit).sum();
        // Income counts only paid (closed) orders: open carts are not sales yet. "Today" is South African time.
        java.time.ZoneId sa = java.time.ZoneId.of("Africa/Johannesburg");
        java.time.LocalDate today = java.time.LocalDate.now(sa);
        List<Sale> todays = mine.stream().filter(s -> s.getCreatedAt().atZone(sa).toLocalDate().equals(today)).toList();
        Map<String, Object> day = Map.of("date", today.toString(), "count", todays.size(),
            "revenue", todays.stream().mapToDouble(Sale::getSubtotal).sum(), "profit", todays.stream().mapToDouble(Sale::getProfit).sum());
        return Map.of("success", true, "count", mine.size(), "revenue", revenue, "profit", profit, "today", day,
            "sales", mine.stream().map(PosController::saleView).toList());
    }

    private static Map<String, Object> saleView(Sale s) {
        Object items;
        try { items = StateStore.JSON.readValue(s.getItemsJson(), List.class); } catch (Exception e) { items = List.of(); }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("saleId", s.getId()); v.put("timestamp", s.getCreatedAt().toString()); v.put("items", items); v.put("subtotal", s.getSubtotal());
        v.put("profit", s.getProfit()); v.put("paymentMethod", s.getPaymentMethod()); v.put("changeDue", s.getChangeDue());
        return v;
    }

    @GetMapping("/inventory/barcode/{barcode}")
    public ResponseEntity<?> byBarcode(@PathVariable String barcode, HttpServletRequest req) {
        String ownerId = AuthInterceptor.current(req).id();
        return inventory.byBarcode(ownerId, barcode)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of("success", true, "item", productView(ownerId, p))))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Barcode not found")));
    }

    @PostMapping("/inventory")
    public ResponseEntity<?> addProduct(@RequestBody Product p, HttpServletRequest req) {
        if (p.getName() == null || p.getName().isBlank() || p.getBarcode() == null || p.getPrice() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid product"));
        }
        String ownerId = AuthInterceptor.current(req).id();
        Product saved = inventory.add(ownerId, p);
        return ResponseEntity.status(201).body(Map.of("success", true, "item", productView(ownerId, saved)));
    }

    @PutMapping("/inventory/{id}/price")
    public ResponseEntity<?> updatePrice(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        double price = body.get("price") instanceof Number n ? n.doubleValue() : 0;
        if (price <= 0) return ResponseEntity.badRequest().body(Map.of("error", "Price must be greater than zero"));
        String ownerId = AuthInterceptor.current(req).id();
        return inventory.setPrice(ownerId, id, price)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of("success", true, "item", productView(ownerId, p))))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Product not found")));
    }

    /** One-step sale (API clients). The POS screen uses the customer-order flow above. */
    @PostMapping("/sale")
    public ResponseEntity<?> sale(@RequestBody SaleRequest req, HttpServletRequest httpReq) {
        String ownerId = AuthInterceptor.current(httpReq).id();
        if (req.getItems() == null || req.getItems().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Cart is empty"));
        List<Map<String, Object>> lines = new ArrayList<>(); double subtotal = 0, cost = 0;
        synchronized (this) {
            for (SaleItem si : req.getItems()) {   // validate everything first so a failed sale never partially deducts stock
                Product p = inventory.find(ownerId, si.getProductId()).orElse(null);
                if (p == null) return ResponseEntity.status(404).body(Map.of("error", "Product not found"));
                if (si.getQuantity() <= 0 || p.getQty() < si.getQuantity()) return ResponseEntity.badRequest().body(Map.of("error", "Insufficient stock: " + p.getName()));
            }
            for (SaleItem si : req.getItems()) {
                Product p = inventory.take(ownerId, si.getProductId(), si.getQuantity());
                subtotal += p.getPrice() * si.getQuantity(); cost += p.getCost() * si.getQuantity();
                lines.add(Map.of("productId", p.getId(), "name", p.getName(), "qty", si.getQuantity(), "unitPrice", p.getPrice(), "lineTotal", p.getPrice() * si.getQuantity()));
            }
        }
        double change = "cash".equalsIgnoreCase(req.getPaymentMethod()) ? Math.max(0, (req.getCashTendered() != null ? req.getCashTendered() : 0) - subtotal) : 0;
        Sale sale = recordSale(new Sale("SALE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT), ownerId, StateStore.write(lines),
            subtotal, subtotal - cost, req.getPaymentMethod(), change));
        List<Map<String, Object>> restock = new ArrayList<>();
        for (SaleItem si : req.getItems()) inventory.find(ownerId, si.getProductId()).flatMap(p -> restockCheck(ownerId, p)).ifPresent(restock::add);
        return ResponseEntity.ok(Map.of("success", true, "sale", saleView(sale), "restock", restock));
    }
}
