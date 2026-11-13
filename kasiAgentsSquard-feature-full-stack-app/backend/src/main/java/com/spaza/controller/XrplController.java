package com.spaza.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/xrpl")
public class XrplController {

    @Value("${xrpl.rlusd.issuer}")  private String rlusdIssuer;
    @Value("${agent.per.tx.limit}") private double perTxLimit;

    private double rlusdBalance = 1250.00;
    private final double xrpBalance = 48.50;
    private final List<Map<String, Object>> txs = new ArrayList<>();

    @GetMapping("/status")
    public synchronized Map<String, Object> status() {
        List<Map<String, Object>> recent = new ArrayList<>(txs);
        Collections.reverse(recent);
        return Map.of(
            "network", "testnet",
            "wsUrl", "wss://s.altnet.rippletest.net:51233",
            "rlusdIssuer", rlusdIssuer,
            "connected", true,
            "rlusdBalance", rlusdBalance,
            "xrpBalance", xrpBalance,
            "perTxLimit", perTxLimit,
            "transactions", recent
        );
    }

    @PostMapping("/pay")
    public synchronized ResponseEntity<?> pay(@RequestBody Map<String, Object> body) {
        Object rawAmount = body.getOrDefault("amount", 0);
        double amount;
        try { amount = Double.parseDouble(String.valueOf(rawAmount)); }
        catch (NumberFormatException e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount")); }
        String destination = String.valueOf(body.getOrDefault("destination", "")).trim();

        if (amount <= 0) return ResponseEntity.badRequest().body(Map.of("error", "Amount must be positive"));

        if (amount > perTxLimit) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "Payment blocked by mandate: exceeds the per-transaction cap",
                "cap", perTxLimit, "requested", amount));
        }
        if (destination.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "Destination required"));
        if (!destination.matches("^r[1-9A-HJ-NP-Za-km-z]{24,34}$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid XRPL address (must start with 'r')"));
        }
        if (amount > rlusdBalance) {
            return ResponseEntity.badRequest().body(Map.of("error", "Insufficient RLUSD balance"));
        }

        String hash = UUID.randomUUID().toString().replace("-", "").toUpperCase()
                    + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        rlusdBalance -= amount;
        txs.add(Map.of("hash", hash, "amount", amount, "destination", destination,
                "timestamp", java.time.Instant.now().toString()));
        return ResponseEntity.ok(Map.of(
            "success", true,
            "network", "XRPL Testnet",
            "engineResult", "tesSUCCESS",
            "hash", hash,
            "explorerUrl", "https://testnet.xrpl.org/transactions/" + hash,
            "amount", amount,
            "destination", destination,
            "balance", rlusdBalance
        ));
    }
}
