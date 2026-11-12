package com.spaza.controller;

import com.spaza.model.Product;
import com.spaza.repository.InMemoryStore;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

/** Demo-only platform controls. No external KYC, lender, or payment provider is represented as live. */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {
  private final InMemoryStore store;
  private final List<Map<String,Object>> audit = Collections.synchronizedList(new ArrayList<>());
  private final List<Map<String,Object>> suppliers = Collections.synchronizedList(new ArrayList<>(List.of(
    new LinkedHashMap<>(Map.of("id","SUP-001","name","Mokoena Meats","country","South Africa","verified",true,"rating",4.8,"deliveryDays",1,"product","Minced Meat","price",65.0,"minOrder",50)),
    new LinkedHashMap<>(Map.of("id","SUP-002","name","Ubuntu Foods Co-op","country","South Africa","verified",true,"rating",4.6,"deliveryDays",2,"product","Maize Meal","price",82.0,"minOrder",30))
  )));
  private final List<Map<String,Object>> orders = Collections.synchronizedList(new ArrayList<>());

  public PlatformController(InMemoryStore store) {
    this.store = store;
    Map<String,Object> initial=order("ORD-2026-0412", "Mokoena Meats", "Minced Meat", 20, 65.0, "SUPPLIER_REVIEW", "Thandi Mokone"); initial.put("createdBy","Spaza Finance Agent"); initial.put("reason","Low-stock group-buy recommendation"); orders.add(initial);
  }

  @GetMapping("/overview") public Map<String,Object> overview() {
    return Map.of("success",true,"profile",Map.of("name","Thandi Mokone","role","Spaza owner","level",2,"levelLabel","Verified","mfaEnabled",true,"language","English","creditScore",712),
      "security",List.of("MFA enabled","Step-up required for high-value approvals","Daily mandate enforced","Audit trail active"),
      "repayment",Map.of("status","SCHEDULED","dueDate",LocalDate.now().plusDays(30).toString(),"amount",950.0,"method","Simulated bank debit mandate"),
      "stokvel",Map.of("name","Mamelodi Central Buying Club","members",5,"splitMethod","By quantity","sharedGuarantee",false));
  }
  @GetMapping("/suppliers") public Map<String,Object> suppliers() { return Map.of("success",true,"suppliers",suppliers); }
  @GetMapping("/notifications") public Map<String,Object> notifications() {
    List<Map<String,Object>> notices = new ArrayList<>();
    for (Product p : store.findAll()) {
      if (p.getQty() <= p.getLowStock()) {
        Map<String,Object> supplier = suppliers.stream().filter(s -> String.valueOf(s.get("product")).toLowerCase().contains(p.getName().split(" ")[0].toLowerCase())).findFirst().orElse(suppliers.get(0));
        String detail = p.getName()+" has "+p.getQty()+" left (reorder level: "+p.getLowStock()+").";
        notices.add(Map.of("id","LOW-OWNER-"+p.getId(),"recipient","OWNER","recipientName","Thandi Mokone","type","LOW_STOCK","title","Restock needed", "message",detail,"action","Review supplier offer","read",false));
        notices.add(Map.of("id","LOW-SUPPLIER-"+p.getId(),"recipient","SUPPLIER","recipientName",supplier.get("name"),"type","STOCK_REQUEST","title","Stock request opportunity", "message","Spaza owner is low on "+p.getName()+". Prepare a verified bulk offer.","action","Respond to order request","read",false));
      }
    }
    return Map.of("success",true,"notifications",notices,"delivery","In-app notifications (SMS/WhatsApp adapter simulated)");
  }
  @GetMapping("/orders") public Map<String,Object> orders() { return Map.of("success",true,"orders",orders); }
  /** Called by the shortage agent only after human rules have enabled automatic drafts. */
  public synchronized Map<String,Object> createAgentDraft(String product, int quantity, double unitPrice) {
    Optional<Map<String,Object>> existing=orders.stream().filter(o -> product.equals(o.get("product")) && !"ESCROW_RELEASED".equals(o.get("status"))).findFirst();
    if(existing.isPresent()) return existing.get();
    String supplier=product.toLowerCase().contains("maize") ? "Ubuntu Foods Co-op" : "Mokoena Meats";
    Map<String,Object> order=order("ORD-AI-"+(1000+orders.size()+1),supplier,product,quantity,unitPrice,"SUPPLIER_REVIEW","Thandi Mokone");
    order.put("createdBy","Spaza Finance Agent"); order.put("reason","Automatic draft: stock below human-approved threshold"); orders.add(0,order); log("AGENT_ORDER_DRAFTED",product+" · "+quantity); return order;
  }
  @PostMapping("/orders") public synchronized Map<String,Object> createOrder(@RequestBody Map<String,Object> input) {
    String supplier=String.valueOf(input.getOrDefault("supplier","Mokoena Meats"));
    String product=String.valueOf(input.getOrDefault("product","Minced Meat"));
    int quantity=((Number)input.getOrDefault("quantity",10)).intValue();
    double price=((Number)input.getOrDefault("unitPrice",65)).doubleValue();
    if(quantity < 1) throw new IllegalArgumentException("Quantity must be at least 1");
    Map<String,Object> order=order("ORD-"+(1000+orders.size()+1),supplier,product,quantity,price,"SUPPLIER_REVIEW","Thandi Mokone"); order.put("createdBy","Spaza Finance Agent"); order.put("reason","Owner-approved agent shortage recommendation");
    orders.add(0,order); log("OWNER_ORDER_REQUESTED",supplier+" · "+quantity+" kg "+product);
    return Map.of("success",true,"order",order,"message","Request sent to the verified supplier. No funds are released until delivery confirmation.");
  }
  @PostMapping("/orders/{id}/supplier-action") public synchronized Map<String,Object> supplierAction(@PathVariable String id,@RequestBody Map<String,Object> input) {
    Map<String,Object> order=orders.stream().filter(o->id.equals(o.get("id"))).findFirst().orElseThrow(()->new NoSuchElementException("Order not found"));
    String action=String.valueOf(input.getOrDefault("action","")).toUpperCase(Locale.ROOT);
    String next=switch(action) { case "ACCEPT" -> "ACCEPTED"; case "DISPATCH" -> "IN_TRANSIT"; case "DELIVER" -> "DELIVERED_PENDING_OWNER"; default -> throw new IllegalArgumentException("Unsupported supplier action"); };
    order.put("status",next); order.put("supplierUpdatedAt",Instant.now().toString()); log("SUPPLIER_"+action,id); return Map.of("success",true,"order",order);
  }
  @PostMapping("/orders/{id}/owner-confirmation") public synchronized Map<String,Object> ownerConfirmation(@PathVariable String id) {
    Map<String,Object> order=orders.stream().filter(o->id.equals(o.get("id"))).findFirst().orElseThrow(()->new NoSuchElementException("Order not found"));
    if(!"DELIVERED_PENDING_OWNER".equals(order.get("status"))) throw new IllegalStateException("Delivery has not been marked ready for confirmation");
    order.put("status","ESCROW_RELEASED"); order.put("ownerConfirmedAt",Instant.now().toString()); log("OWNER_DELIVERY_CONFIRMED",id); return Map.of("success",true,"order",order,"message","Delivery confirmed. Demo escrow released directly to the supplier.");
  }
  @PostMapping("/suppliers") public Map<String,Object> addSupplier(@RequestBody Map<String,Object> input) {
    Map<String,Object> s=new LinkedHashMap<>(input); s.put("id","SUP-"+(suppliers.size()+1)); s.put("verified",false); s.put("rating",0); s.put("deliveryDays",3); suppliers.add(s); log("SUPPLIER_SUBMITTED",String.valueOf(s.getOrDefault("name","Supplier"))); return Map.of("success",true,"supplier",s,"message","Supplier submitted for four-eyes verification review.");
  }
  @PostMapping("/step-up") public Map<String,Object> stepUp(@RequestBody Map<String,Object> input) {
    String action=String.valueOf(input.getOrDefault("action","sensitive action")); log("STEP_UP_APPROVED",action); return Map.of("success",true,"approved",true,"message","Demo OTP/passkey step-up approved for "+action);
  }
  @GetMapping("/audit") public Map<String,Object> getAudit() { return Map.of("success",true,"events",audit); }
  private void log(String type,String detail) { audit.add(0,Map.of("type",type,"detail",detail,"at",Instant.now().toString(),"actor","Thandi Mokone")); }
  private Map<String,Object> order(String id,String supplier,String product,int quantity,double price,String status,String owner) {
    Map<String,Object> o=new LinkedHashMap<>(); o.put("id",id); o.put("owner",owner); o.put("supplier",supplier); o.put("product",product); o.put("quantity",quantity); o.put("unitPrice",price); o.put("total",quantity*price); o.put("escrow","HELD"); o.put("status",status); o.put("createdAt",Instant.now().toString()); return o;
  }
}
