package com.spaza.controller;

import com.spaza.model.SaleItem;
import com.spaza.service.UsdcInvoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/usdc")
public class UsdcController {
    private final UsdcInvoiceService invoices;
    public UsdcController(UsdcInvoiceService invoices) { this.invoices = invoices; }

    @GetMapping("/config") public Map<String,Object> config() { return invoices.configuration(); }
    @PostMapping("/invoices") public ResponseEntity<?> create(@RequestBody Map<String, List<SaleItem>> body) {
        try { return ResponseEntity.status(201).body(invoices.createInvoice(body.get("items"))); }
        catch (IllegalArgumentException | IllegalStateException e) { return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage())); }
    }
    @PostMapping("/invoices/{id}/verify") public ResponseEntity<?> verify(@PathVariable String id, @RequestBody Map<String,String> body) {
        try { return ResponseEntity.ok(invoices.verify(id, body.get("transactionHash"))); }
        catch (IllegalArgumentException e) { return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage())); }
        catch (IllegalStateException e) { return ResponseEntity.status(503).body(Map.of("error", e.getMessage())); }
    }
}
