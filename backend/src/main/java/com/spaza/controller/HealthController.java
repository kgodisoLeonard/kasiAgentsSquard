package com.spaza.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "healthy",
            "service", "Kasi Agents Squad API (Java) · Spaza to the World",
            "version", "1.0.0",
            "timestamp", java.time.Instant.now().toString(),
            "network", "testnet"
        );
    }
}
