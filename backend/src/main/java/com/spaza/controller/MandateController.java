package com.spaza.controller;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/mandates")
public class MandateController {

    private final List<Map<String, Object>> mandates = new ArrayList<>();

    public MandateController() {
        mandates.add(Map.of(
            "id", "MANDATE-01",
            "title", "Daily Supplier Restock Mandate",
            "perTxCap", 1000.0, "dailyCap", 5000.0, "spentToday", 1250.0,
            "expires", "2026-12-31", "status", "ACTIVE"));
        mandates.add(Map.of(
            "id", "MANDATE-02",
            "title", "Emergency Shortage Group Buy Cap",
            "perTxCap", 500.0, "dailyCap", 2500.0, "spentToday", 950.0,
            "expires", "2026-11-15", "status", "ACTIVE"));
    }

    @GetMapping
    public Map<String, Object> list() { return Map.of("success", true, "mandates", mandates); }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> m = new HashMap<>(body);
        m.put("id", "MANDATE-0" + (mandates.size() + 1));
        m.put("status", "ACTIVE");
        m.put("spentToday", 0.0);
        mandates.add(m);
        return Map.of("success", true, "mandate", m);
    }
}
