package com.spaza.notify;

import com.spaza.finance.GroupOrder;
import com.spaza.finance.GroupOrderService;
import com.spaza.finance.SupplierRegistry;
import com.spaza.model.AppUser;
import com.spaza.persistence.StateStore;
import com.spaza.persistence.Ttl;
import com.spaza.repository.AppUserRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * The shared update feed for group orders. Every member shop (and the supplier) is told about each step of an order
 * they are part of: invited, another shop paid its share, funded, accepted, dispatched, delivered, confirmed, completed,
 * cancelled/refunded, disputed. Updates are stored in the database (state store) so they survive restarts.
 */
@Service
public class ShopUpdates {
  private static final String KIND = "shop-update";
  private static final int KEEP_IN_MEMORY = 3000;

  private final StateStore store;
  private final SupplierRegistry suppliers;
  private final Clock clock;
  private final Ttl<List<AppUser>> users;
  private final LinkedList<Map<String,Object>> feed = new LinkedList<>();   // newest first

  public ShopUpdates(StateStore store, SupplierRegistry suppliers, AppUserRepository userRepo, Clock clock, GroupOrderService orders) {
    this.store = store; this.suppliers = suppliers; this.clock = clock;
    this.users = new Ttl<>(Duration.ofSeconds(30), userRepo::findAll);
    List<Map<String,Object>> saved = new ArrayList<>();
    for (Object m : store.loadAll(KIND, Map.class)) { @SuppressWarnings("unchecked") Map<String,Object> u = (Map<String,Object>) m; saved.add(u); }
    saved.sort(Comparator.comparing((Map<String,Object> m) -> String.valueOf(m.get("at"))).reversed());
    feed.addAll(saved.subList(0, Math.min(saved.size(), KEEP_IN_MEMORY)));
    orders.onEvent(this::onOrderEvent);
  }

  /** Updates for one account, newest first. */
  public synchronized List<Map<String,Object>> forUser(String userId, int limit) {
    return feed.stream().filter(u -> userId.equals(u.get("recipientId"))).limit(limit).toList();
  }

  private String shop(String userId) {
    return users.get().stream().filter(u -> u.getId().equals(userId)).findFirst().map(AppUser::getBusinessName).orElse(userId);
  }

  private void onOrderEvent(GroupOrderService.OrderEvent e) {
    GroupOrder o = e.order();
    List<String> members = o.shares().stream().map(GroupOrder.Share::ownerId).toList();
    String what = o.product() + " (" + o.id() + ")";
    String subject = e.inputs() != null && e.inputs().get("ownerId") != null ? String.valueOf(e.inputs().get("ownerId")) : e.actorId();
    switch (e.action()) {
      case "GROUP_ORDER_DRAFTED" -> {
        if (members.size() < 2 || "supplier-redirect".equals(e.actorId())) return;   // restocks notify separately; redirects below
        String by = "HUMAN".equals(e.actorType()) ? shop(e.actorId()) : "The buying club";
        for (String m : members) if (!m.equals(e.actorId()))
          post(m, o, "INVITED", by + " invited you to a group order for " + what + ". Approve your share to join.");
      }
      case "ESCROW_CREATED" -> {
        String amount = o.share(subject).map(s -> "R" + s.total().toPlainString()).orElse("");
        for (String m : members) if (!m.equals(subject)) post(m, o, "SHARE_PAID", shop(subject) + " paid its share " + amount + " for " + what + ".");
      }
      case "SHARE_DECLINED" -> { for (String m : members) if (!m.equals(e.actorId())) post(m, o, "SHARE_DECLINED", shop(e.actorId()) + " declined " + what + "."); }
      case "ORDER_FUNDED" -> {
        for (String m : members) post(m, o, "ORDER_FUNDED", what + " is fully paid into escrow and sent to " + supplierName(o) + ".");
        supplierAccount(o).ifPresent(s -> post(s, o, "NEW_ORDER", "New paid order " + what + ": " + o.totalQuantity() + " units for " + members.size() + " shop(s)."));
      }
      case "ORDER_CANCELLED" -> { for (String m : members) post(m, o, "CANCELLED", what + " was cancelled; any payment went back to your wallet."); }
      case "ORDER_REFUNDED" -> { for (String m : members) post(m, o, "REFUNDED", what + " was refunded; your payment went back to your wallet."); }
      case "SUPPLIER_ACCEPT" -> { for (String m : members) post(m, o, "ACCEPTED", supplierName(o) + " accepted " + what + "."); }
      case "SUPPLIER_DISPATCH" -> { for (String m : members) post(m, o, "DISPATCHED", what + " is on the way from " + supplierName(o) + "."); }
      case "SUPPLIER_DELIVER" -> { for (String m : members) post(m, o, "DELIVERED", supplierName(o) + " says " + what + " was delivered. Confirm once it is in your shop."); }
      case "DELIVERY_CONFIRMED" -> { for (String m : members) if (!m.equals(e.actorId())) post(m, o, "MEMBER_CONFIRMED", shop(e.actorId()) + " confirmed delivery of " + what + "."); }
      case "ORDER_COMPLETED" -> {
        for (String m : members) post(m, o, "COMPLETED", what + " is complete: everyone confirmed delivery and " + supplierName(o) + " was paid.");
        supplierAccount(o).ifPresent(s -> post(s, o, "PAID", "You were paid for " + what + "."));
      }
      case "DISPUTE_OPENED" -> {
        for (String m : members) if (!m.equals(e.actorId())) post(m, o, "DISPUTED", shop(e.actorId()) + " opened a dispute on " + what + ". Escrow is held until a reviewer decides.");
        supplierAccount(o).ifPresent(s -> post(s, o, "DISPUTED", "A buyer opened a dispute on " + what + "."));
      }
      case "SHARE_APPROVED_BY_AGENT" -> post(subject, o, "AGENT_APPROVED", "The agent approved your share of " + what + " within your mandate and Human Rules.");
      case "AGENT_APPROVAL_BLOCKED" -> post(subject, o, "NEEDS_APPROVAL", "The agent could not pay your share of " + what + ": "
          + o.share(subject).map(GroupOrder.Share::blockedReason).orElse("blocked") + ". Approve it yourself or adjust your mandate.");
      case "GROUP_ORDER_REDIRECTED" -> { for (String m : members) post(m, o, "REDIRECTED", what + " was redirected to " + supplierName(o) + ": "
          + e.inputs().get("from") + " did not have enough stock."); }
      case "SUPPLIER_DECLINED" -> { for (String m : members) post(m, o, "SUPPLIER_DECLINED", supplierName(o) + " declined " + what + " (" + e.inputs().get("reason")
          + "). Your payment was refunded."); }
      case "ORDER_REDIRECTED" -> { for (String m : members) post(m, o, "REDIRECTED", what + " moved to " + e.inputs().get("to") + " as a new order. The agent approves your share if your rules allow."); }
      default -> { }
    }
  }

  private String supplierName(GroupOrder o) { return suppliers.find(o.supplierId()).map(SupplierRegistry.SupplierInfo::name).orElse(o.supplierId()); }
  private Optional<String> supplierAccount(GroupOrder o) { return suppliers.find(o.supplierId()).map(SupplierRegistry.SupplierInfo::accountUserId).filter(Objects::nonNull); }

  private synchronized void post(String recipientId, GroupOrder o, String type, String message) {
    String id = "U-" + UUID.randomUUID().toString().substring(0, 12);
    Map<String,Object> u = new LinkedHashMap<>();
    u.put("id", id); u.put("recipientId", recipientId); u.put("orderId", o.id()); u.put("product", o.product());
    u.put("type", type); u.put("message", message); u.put("at", clock.instant().toString());
    feed.addFirst(u);
    if (feed.size() > KEEP_IN_MEMORY) feed.removeLast();
    store.save(KIND, id, u);
  }
}
