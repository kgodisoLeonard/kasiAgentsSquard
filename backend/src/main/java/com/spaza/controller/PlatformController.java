package com.spaza.controller;

import com.spaza.finance.GroupOrder;
import com.spaza.finance.GroupOrderService;
import com.spaza.finance.SupplierRegistry;
import com.spaza.model.AppUser;
import com.spaza.model.OwnerSupplier;
import com.spaza.model.Product;
import com.spaza.model.SupplierRating;
import com.spaza.repository.AppUserRepository;
import com.spaza.repository.InMemoryStore;
import com.spaza.repository.OwnerSupplierRepository;
import com.spaza.repository.SupplierRatingRepository;
import com.spaza.security.AuthInterceptor;
import com.spaza.security.AuthService;
import com.spaza.security.AuthService.AuthUser;
import com.spaza.security.RequireRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Marketplace directory, supplier offers, ratings and the trust-centre overview.
 * Orders, escrow and payments live in GroupOrderController / GroupOrderService.
 * SIMULATED here: credit/repayment figures and the stokvel profile (no lender or bank is connected).
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {
  private final InMemoryStore store;
  private final AppUserRepository users;
  private final SupplierRatingRepository ratings;
  private final OwnerSupplierRepository ownerSuppliers;
  private final SupplierRegistry registry;
  private final GroupOrderService orders;
  /** Product offers published by suppliers. Supplier identity and verification come from SupplierRegistry. */
  private final List<Map<String,Object>> offers = List.of(
    offer("SUP-001", "Minced Meat", 65.0, 50, 1),
    offer("SUP-002", "Maize Meal", 82.0, 30, 2));

  public PlatformController(InMemoryStore store, AppUserRepository users, SupplierRatingRepository ratings, OwnerSupplierRepository ownerSuppliers,
                            SupplierRegistry registry, GroupOrderService orders) {
    this.store = store; this.users = users; this.ratings = ratings; this.ownerSuppliers = ownerSuppliers; this.registry = registry; this.orders = orders;
  }

  @GetMapping("/overview") public Map<String,Object> overview(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    AppUser user = users.findById(u.id()).orElseThrow();
    return Map.of("success", true,
      "profile", Map.of("name", user.getName(), "role", user.getRole(), "level", AuthService.level(user), "levelLabel", user.isVerified() ? "Verified" : "Basic",
          "mfaEnabled", true, "mfaNote", "OTP on every sign-in (SMS delivery simulated)", "language", "English"),
      "security", List.of("MFA on every sign-in (OTP delivery simulated)", "Step-up code for large orders and mandate changes", "Mandate checked before every payment", "Hash-chained audit trail"),
      "repayment", Map.of("status", "SIMULATED", "dueDate", LocalDate.now().plusDays(30).toString(), "amount", 950.0, "method", "Simulated bank debit mandate (no lender connected)"),
      "stokvel", Map.of("name", "Mamelodi Central Buying Club", "members", users.findAll().stream().filter(x -> "OWNER".equals(x.getRole())).count(), "splitMethod", "By quantity", "sharedGuarantee", false));
  }

  /** Offers from verified, unscreened suppliers only, with the supplier's trust data attached. */
  @GetMapping("/suppliers") public Map<String,Object> suppliers() {
    List<Map<String,Object>> list = new ArrayList<>();
    for (Map<String,Object> o : offers) registry.find(String.valueOf(o.get("id"))).ifPresent(s -> {
      Map<String,Object> v = new LinkedHashMap<>(o);
      v.put("name", s.name()); v.put("verified", s.canReceiveOrders()); v.put("country", "South Africa");
      v.put("completedDeliveries", s.completedDeliveries()); v.put("onTrial", s.onTrial()); v.put("rating", averageRating(s.id(), 0));
      list.add(v);
    });
    return Map.of("success", true, "suppliers", list);
  }

  public Optional<Map<String,Object>> offerFor(String productName) {
    String wanted = productName.toLowerCase(Locale.ROOT);
    return offers.stream().filter(o -> wanted.startsWith(String.valueOf(o.get("product")).toLowerCase(Locale.ROOT)))
        .filter(o -> registry.find(String.valueOf(o.get("id"))).map(SupplierRegistry.SupplierInfo::canReceiveOrders).orElse(false))
        .findFirst().map(o -> { Map<String,Object> v = new LinkedHashMap<>(o); v.put("name", registry.find(String.valueOf(o.get("id"))).get().name()); return v; });
  }

  @GetMapping("/directory") public Map<String,Object> directory() {
    List<Map<String,Object>> spazaDirectory = users.findAll().stream().filter(u -> "OWNER".equals(u.getRole()))
        .map(u -> Map.<String,Object>of("id", u.getId(), "name", u.getName(), "businessName", u.getBusinessName(), "verified", u.isVerified(), "role", u.getRole())).toList();
    return Map.of("success", true, "suppliers", supplierDirectory(), "spazas", spazaDirectory);
  }

  private List<Map<String,Object>> supplierDirectory() {
    List<Map<String,Object>> list = new ArrayList<>();
    for (SupplierRegistry.SupplierInfo s : registry.all()) {
      List<SupplierRating> entries = ratings.findBySupplierIdOrderByCreatedAtDesc(s.id());
      Map<String,Object> v = new LinkedHashMap<>();
      v.put("id", s.id()); v.put("businessName", s.name()); v.put("name", s.name()); v.put("country", "South Africa");
      v.put("verified", s.verified()); v.put("screeningHit", s.sanctionsHit()); v.put("onTrial", s.onTrial());
      v.put("completedDeliveries", s.completedDeliveries()); v.put("deliveryDays", s.id().equals("SUP-002") ? 2 : s.id().equals("SUP-001") ? 1 : 3);
      v.put("rating", averageRating(s.id(), 0)); v.put("ratingCount", entries.size()); v.put("reviews", entries.stream().map(this::ratingView).toList());
      list.add(v);
    }
    return list;
  }

  private double averageRating(String supplierId, double fallback) {
    double avg = ratings.findBySupplierIdOrderByCreatedAtDesc(supplierId).stream().mapToInt(SupplierRating::getRating).average().orElse(fallback);
    return Math.round(avg * 10) / 10.0;
  }

  /** Owners can rate a supplier only for a completed order they paid into. */
  @PostMapping("/suppliers/{supplierId}/ratings") @RequireRole("OWNER")
  public synchronized Map<String,Object> rateSupplier(@PathVariable String supplierId, @RequestBody Map<String,Object> input, HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    String orderId = String.valueOf(input.getOrDefault("orderId", "")).trim();
    int rating = input.get("rating") instanceof Number n ? n.intValue() : 0;
    String comment = String.valueOf(input.getOrDefault("comment", "")).trim();
    if (comment.length() > 500) comment = comment.substring(0, 500);
    if (rating < 1 || rating > 5) throw new IllegalArgumentException("Rating must be between 1 and 5 stars");
    if (orderId.isBlank()) throw new IllegalArgumentException("A delivered order is required before rating a supplier");
    GroupOrder order = orders.find(orderId).orElseThrow(() -> new NoSuchElementException("Order not found"));
    if (!order.supplierId().equals(supplierId)) throw new IllegalArgumentException("This order was not supplied by that supplier");
    if (order.status() != GroupOrder.Status.COMPLETED) throw new IllegalStateException("Only delivered orders can be rated");
    if (order.share(u.id()).map(s -> s.status() != GroupOrder.ShareStatus.RELEASED).orElse(true)) throw new SecurityException("Only buyers who paid for this order can rate it");
    if (ratings.findBySupplierIdAndOrderId(supplierId, orderId + ":" + u.id()).isPresent()) throw new IllegalStateException("You have already rated this order");
    String supplierName = registry.find(supplierId).map(SupplierRegistry.SupplierInfo::name).orElse(supplierId);
    SupplierRating saved = ratings.save(new SupplierRating(UUID.randomUUID().toString(), supplierId, supplierName, u.id(), u.name(), orderId + ":" + u.id(), rating, comment));
    return Map.of("success", true, "rating", ratingView(saved), "message", "Supplier rating saved");
  }

  @GetMapping("/notifications") public Map<String,Object> notifications(HttpServletRequest req) {
    AuthUser u = AuthInterceptor.current(req);
    List<Map<String,Object>> notices = new ArrayList<>();
    for (Product p : store.findAll()) {
      if (p.getQty() <= p.getLowStock()) {
        notices.add(Map.of("id", "LOW-OWNER-" + p.getId(), "recipient", "OWNER", "recipientName", u.name(), "type", "LOW_STOCK", "title", "Restock needed",
            "message", p.getName() + " has " + p.getQty() + " left (reorder level: " + p.getLowStock() + ").", "action", "Review supplier offer", "read", false));
      }
    }
    return Map.of("success", true, "notifications", notices, "delivery", "In-app notifications (SMS/WhatsApp adapter simulated)");
  }

  /** The supplier stores an owner has chosen to work with. Only the owner themself can read or change their list. */
  @GetMapping("/owners/{ownerId}/suppliers") @RequireRole("OWNER")
  public Map<String,Object> ownerSuppliers(@PathVariable String ownerId, HttpServletRequest req) {
    requireSelf(ownerId, req);
    return Map.of("success", true, "suppliers", ownerSupplierViews(ownerId));
  }

  @PostMapping("/owners/{ownerId}/suppliers") @RequireRole("OWNER")
  public synchronized Map<String,Object> addOwnerSupplier(@PathVariable String ownerId, @RequestBody Map<String,Object> input, HttpServletRequest req) {
    requireSelf(ownerId, req);
    Object raw = input.get("supplierId");
    String supplierId = raw == null ? "" : String.valueOf(raw).trim();
    if (supplierId.isEmpty()) throw new IllegalArgumentException("Choose a supplier store to add");
    Map<String,Object> supplier = supplierDirectory().stream().filter(s -> supplierId.equals(s.get("id"))).findFirst().orElseThrow(() -> new NoSuchElementException("Supplier store not found"));
    if (ownerSuppliers.findByOwnerIdAndSupplierId(ownerId, supplierId).isPresent()) throw new IllegalStateException(supplier.get("businessName") + " is already in your suppliers");
    ownerSuppliers.save(new OwnerSupplier(UUID.randomUUID().toString(), ownerId, supplierId, String.valueOf(supplier.get("businessName"))));
    return Map.of("success", true, "suppliers", ownerSupplierViews(ownerId), "message", supplier.get("businessName") + " added to your suppliers");
  }

  @DeleteMapping("/owners/{ownerId}/suppliers/{supplierId}") @RequireRole("OWNER")
  public synchronized Map<String,Object> removeOwnerSupplier(@PathVariable String ownerId, @PathVariable String supplierId, HttpServletRequest req) {
    requireSelf(ownerId, req);
    OwnerSupplier entry = ownerSuppliers.findByOwnerIdAndSupplierId(ownerId, supplierId).orElseThrow(() -> new NoSuchElementException("That supplier is not in your list"));
    ownerSuppliers.delete(entry);
    return Map.of("success", true, "suppliers", ownerSupplierViews(ownerId), "message", entry.getSupplierName() + " removed from your suppliers");
  }

  private void requireSelf(String ownerId, HttpServletRequest req) {
    if (!AuthInterceptor.current(req).id().equals(ownerId)) throw new SecurityException("You can only manage your own supplier list");
  }

  private List<Map<String,Object>> ownerSupplierViews(String ownerId) {
    Map<String,Map<String,Object>> byId = new HashMap<>();
    supplierDirectory().forEach(s -> byId.put(String.valueOf(s.get("id")), s));
    return ownerSuppliers.findByOwnerIdOrderByAddedAtAsc(ownerId).stream().map(e -> byId.get(e.getSupplierId())).filter(Objects::nonNull).toList();
  }

  private Map<String,Object> ratingView(SupplierRating r) {
    return Map.of("id", r.getId(), "supplierId", r.getSupplierId(), "supplierName", r.getSupplierName(), "ownerName", r.getOwnerName(), "orderId", r.getOrderId().split(":")[0],
        "rating", r.getRating(), "comment", r.getComment() == null ? "" : r.getComment(), "createdAt", r.getCreatedAt().toString());
  }

  private static Map<String,Object> offer(String id, String product, double price, int minOrder, int deliveryDays) {
    Map<String,Object> o = new LinkedHashMap<>();
    o.put("id", id); o.put("product", product); o.put("price", price); o.put("minOrder", minOrder); o.put("deliveryDays", deliveryDays);
    return o;
  }
}
