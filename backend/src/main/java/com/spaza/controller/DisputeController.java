package com.spaza.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    @GetMapping("/{ref}/evidence-pack")
    public ResponseEntity<?> evidencePack(@PathVariable String ref) throws Exception {
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("caseRef", ref);
        dispute.put("type", "Non-Payment of Credit Order");
        dispute.put("claimant", "Mokoena Meats (Pty) Ltd");
        dispute.put("respondent", "Thandi Mokone");
        dispute.put("amount", 1000.00);
        dispute.put("status", "Decision Final (Closed)");
        dispute.put("evidence", Map.of(
            "creditAgreement", "CA-0412-B",
            "invoice", "INV-0412-B",
            "deliveryWaybill", "WB-88231",
            "buyerConfirmation", "All received, OK (20 Aug 2026)"
        ));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(dispute.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("documentTitle", "SPAZA EATS EVIDENCE PACK - " + ref);
        result.put("status", dispute.get("status"));
        result.put("sha256Fingerprint", hex.toString());
        result.put("verificationCode", ref + "-" + hex.substring(0, 8).toUpperCase());
        result.put("verificationUrl", "https://verify.spazaeats.example/" + ref);
        result.put("content", dispute);
        return ResponseEntity.ok(result);
    }
}
