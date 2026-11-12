package com.spaza.controller;
import com.spaza.model.Product;
import com.spaza.repository.InMemoryStore;
import com.spaza.repository.AgentPolicy;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final InMemoryStore store; private final AgentPolicy policy; private final PlatformController platform;
    public AgentController(InMemoryStore store, AgentPolicy policy, PlatformController platform){this.store=store;this.policy=policy;this.platform=platform;}

    /** The agent's only configuration input: a read-only guardrail snapshot. Mandates are never exposed here. */
    @GetMapping("/rules")
    public Map<String,Object> rules() {
        return Map.of("success", true, "rules", policy.get(),
                "access", "READ_ONLY", "restrictedResources", List.of("mandates", "wallets", "bank_details", "identity_documents"));
    }

    /** Enforces the human-owned allow-list before any XRPL payment draft is created. */
    @PostMapping("/purchase-draft")
    public org.springframework.http.ResponseEntity<?> purchaseDraft(@RequestBody Map<String,Object> body) {
        String item=String.valueOf(body.getOrDefault("item", ""));
        double price=((Number)body.getOrDefault("unitPrice",0)).doubleValue();
        int quantity=((Number)body.getOrDefault("quantity",0)).intValue();
        boolean verified=Boolean.TRUE.equals(body.get("verifiedSupplier"));
        if(quantity<1 || price<=0)return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error","Item price and quantity are required"));
        Map<String,Object> result=policy.approvePurchase(item,price,quantity,verified);
        return Boolean.TRUE.equals(result.get("allowed"))?org.springframework.http.ResponseEntity.ok(result):org.springframework.http.ResponseEntity.status(403).body(result);
    }

    @GetMapping("/shortages")
    public Map<String, Object> shortages() {
        List<Map<String,Object>> groups=new ArrayList<>(); List<Map<String,Object>> signals=new ArrayList<>();
        for(Product p:store.findAll()) if(p.getQty() < policy.warningThreshold()) {
            int required=Math.max(10,p.getLowStock()*3-p.getQty()); double delivery=250.0; double wholesale=Math.round(p.getCost()*1.08*100.0)/100.0;
            boolean auto=policy.autoOrderEnabled() && p.getQty() < policy.autoOrderThreshold();
            if (auto) platform.createAgentDraft(p.getName(), required, wholesale);
            signals.add(Map.of("product",p.getName(),"stock",p.getQty(),"warning",policy.warningThreshold(),"autoOrder",auto));
            groups.add(Map.ofEntries(Map.entry("id","GB-"+p.getId().substring(5).toUpperCase()),Map.entry("product",p.getName()),Map.entry("targetQty",required*5),Map.entry("currentQty",required*3),Map.entry("unitPrice",p.getPrice()),Map.entry("wholesalePrice",wholesale),Map.entry("deliveryFee",delivery),Map.entry("status",auto?"AUTO_ORDER_DRAFTED":"AWAITING_MEMBER_APPROVAL"),Map.entry("deadline","18h"),Map.entry("community","Mamelodi Central Buying Club"),Map.entry("memberCount",5),Map.entry("costSplit",Map.of("method","BY_QUANTITY","yourShare",required,"goods",required*wholesale,"delivery",delivery/5,"total",required*wholesale+delivery/5))));
        }
        return Map.of(
            "success", true,
            "shortagesDetected", signals.size(), "signals",signals,
            "recommendation", "The agent groups verified spazas with the same shortage and splits goods and delivery costs by quantity.",
            "groupOrders", groups, "humanRules", policy.get()
        );
    }

    @PostMapping("/group-buy/approve")
    public Map<String, Object> approve(@RequestBody Map<String, Object> body) {
        int qty = ((Number) body.getOrDefault("quantity", 10)).intValue();
        boolean stepUp=qty*65.0 > 1000;
        return Map.of(
            "success", true,
            "message", "Group order approved. Funds secured in escrow. 30-day debit mandate registered.",
            "quantityApproved", qty,
            "totalDueIn30Days", qty * 65.0 + 50.0, "stepUpRequired",stepUp,
            "payment","XRPL Testnet simulation; owner confirmation required before any payment"
        );
    }
}
