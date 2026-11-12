package com.spaza.controller;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @GetMapping("/shortages")
    public Map<String, Object> shortages() {
        return Map.of(
            "success", true,
            "shortagesDetected", 2,
            "recommendation", "Community Mamelodi Central has 5 shops low on Minced Meat. Group buy recommended to save R25/kg.",
            "groupOrders", List.of(Map.of(
                "id", "GB-2026-0412",
                "product", "Minced Meat",
                "targetQty", 50, "currentQty", 40,
                "unitPrice", 90.0, "wholesalePrice", 65.0,
                "deliveryFee", 250.0, "status", "OPEN",
                "deadline", "18h 42m",
                "community", "Mamelodi Central Buying Club"
            ))
        );
    }

    @PostMapping("/group-buy/approve")
    public Map<String, Object> approve(@RequestBody Map<String, Object> body) {
        int qty = ((Number) body.getOrDefault("quantity", 10)).intValue();
        return Map.of(
            "success", true,
            "message", "Group order approved. Funds secured in escrow. 30-day debit mandate registered.",
            "quantityApproved", qty,
            "totalDueIn30Days", qty * 90.0 + 50.0
        );
    }
}
