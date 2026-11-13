package com.spaza.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaza.model.Product;
import com.spaza.model.SaleItem;
import com.spaza.repository.InMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates POS invoices and verifies a customer-signed USDC payment directly
 * against an XRPL JSON-RPC node. This service never receives a wallet seed or
 * signs a transaction; custody remains with the customer and merchant wallets.
 */
@Service
public class UsdcInvoiceService {
    private final InMemoryStore store;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Invoice> invoices = new ConcurrentHashMap<>();
    private final Set<String> usedTransactionHashes = ConcurrentHashMap.newKeySet();

    @Value("${usdc.network:testnet}") private String network;
    @Value("${usdc.rpc.url:https://s.altnet.rippletest.net:51234}") private String rpcUrl;
    @Value("${usdc.currency:5553444300000000000000000000000000000000}") private String currency;
    @Value("${usdc.issuer:rHuGNhqTG32mfmAvWA8hUyWRLV3tCSwKQt}") private String issuer;
    @Value("${usdc.merchant.address:}") private String merchantAddress;
    @Value("${usdc.zar-per-usdc:18.00}") private BigDecimal zarPerUsdc;

    public UsdcInvoiceService(InMemoryStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public Map<String, Object> configuration() {
        return Map.of(
            "network", network,
            "currency", "USDC",
            "issuer", issuer,
            "merchantAddress", merchantAddress,
            "configured", isConfigured(),
            "zarPerUsdc", zarPerUsdc.toPlainString()
        );
    }

    public synchronized Map<String, Object> createInvoice(List<SaleItem> items) {
        if (!isConfigured()) throw new IllegalStateException("USDC is not configured: set USDC_MERCHANT_ADDRESS on the server");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("Cart is empty");

        BigDecimal zarTotal = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SaleItem item : items) {
            Product product = store.findById(item.getProductId()).orElseThrow(() -> new IllegalArgumentException("Product not found"));
            if (item.getQuantity() <= 0 || product.getQty() < item.getQuantity()) throw new IllegalArgumentException("Insufficient stock: " + product.getName());
            BigDecimal lineTotal = BigDecimal.valueOf(product.getPrice()).multiply(BigDecimal.valueOf(item.getQuantity()));
            zarTotal = zarTotal.add(lineTotal);
            lines.add(Map.of("productId", product.getId(), "quantity", item.getQuantity(), "lineTotalZar", lineTotal.toPlainString()));
        }
        BigDecimal usdcAmount = zarTotal.divide(zarPerUsdc, 6, RoundingMode.HALF_UP);
        String id = "USDC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Invoice invoice = new Invoice(id, lines, zarTotal, usdcAmount, Instant.now().plus(15, ChronoUnit.MINUTES));
        invoices.put(id, invoice);
        return invoice.response();
    }

    public synchronized Map<String, Object> verify(String invoiceId, String transactionHash) {
        Invoice invoice = invoices.get(invoiceId);
        if (invoice == null) throw new IllegalArgumentException("USDC invoice not found");
        if (invoice.expiresAt.isBefore(Instant.now())) throw new IllegalArgumentException("USDC invoice has expired; create a new invoice");
        if (invoice.status.equals("PAID")) return invoice.response();
        if (transactionHash == null || !transactionHash.matches("^[A-Fa-f0-9]{64}$")) throw new IllegalArgumentException("Enter a valid 64-character XRPL transaction hash");
        if (usedTransactionHashes.contains(transactionHash.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("This transaction has already been used");

        JsonNode tx = fetchTransaction(transactionHash);
        if (!tx.path("validated").asBoolean(false)) throw new IllegalArgumentException("Transaction is not validated yet");
        if (!"tesSUCCESS".equals(tx.path("meta").path("TransactionResult").asText())) throw new IllegalArgumentException("XRPL payment did not succeed");
        if (!"Payment".equals(tx.path("TransactionType").asText())) throw new IllegalArgumentException("Transaction is not a payment");
        if (!merchantAddress.equals(tx.path("Destination").asText())) throw new IllegalArgumentException("Payment was sent to a different merchant address");

        // Use delivered_amount where XRPL supplies it: a successful Payment can be partial.
        JsonNode amount = tx.path("meta").path("delivered_amount");
        if (!amount.isObject()) amount = tx.path("Amount");
        if (!amount.isObject() || !currency.equalsIgnoreCase(amount.path("currency").asText()) || !issuer.equals(amount.path("issuer").asText())) {
            throw new IllegalArgumentException("Payment is not official USDC on the configured XRPL network");
        }
        BigDecimal sent;
        try { sent = new BigDecimal(amount.path("value").asText()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid USDC amount in transaction"); }
        if (sent.compareTo(invoice.usdcAmount) != 0) throw new IllegalArgumentException("Incorrect USDC amount: expected " + invoice.usdcAmount.toPlainString());

        invoice.status = "PAID";
        invoice.transactionHash = transactionHash.toUpperCase(Locale.ROOT);
        usedTransactionHashes.add(invoice.transactionHash);
        return invoice.response();
    }

    public synchronized Map<String, Object> consumeVerifiedInvoice(String invoiceId, List<SaleItem> items, InMemoryStore ignored) {
        Invoice invoice = invoices.get(invoiceId);
        if (invoice == null || !"PAID".equals(invoice.status)) return Map.of("allowed", false, "reason", "A verified USDC invoice is required before completing this sale");
        if (invoice.status.equals("CONSUMED")) return Map.of("allowed", false, "reason", "This USDC invoice has already been used");
        if (!sameItems(invoice.items, items)) return Map.of("allowed", false, "reason", "Cart no longer matches the paid USDC invoice");
        invoice.status = "CONSUMED";
        return Map.of("allowed", true);
    }

    private boolean sameItems(List<Map<String, Object>> invoiceItems, List<SaleItem> requestItems) {
        if (invoiceItems.size() != requestItems.size()) return false;
        Map<String, Integer> expected = new HashMap<>(), received = new HashMap<>();
        for (Map<String, Object> line : invoiceItems) expected.merge((String) line.get("productId"), (Integer) line.get("quantity"), Integer::sum);
        for (SaleItem item : requestItems) received.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        if (!expected.equals(received)) return false;
        return true;
    }

    private JsonNode fetchTransaction(String hash) {
        try {
            String payload = mapper.writeValueAsString(Map.of("method", "tx", "params", List.of(Map.of("transaction", hash, "binary", false))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(rpcUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            JsonNode response = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            JsonNode result = response.path("result");
            if (!result.path("error").isMissingNode()) throw new IllegalArgumentException("XRPL could not find this transaction yet");
            return result;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Could not contact the XRPL node to verify payment"); }
    }

    private boolean isConfigured() { return merchantAddress != null && merchantAddress.matches("^r[1-9A-HJ-NP-Za-km-z]{24,34}$"); }

    private final class Invoice {
        final String id; final List<Map<String, Object>> items; final BigDecimal zarTotal; final BigDecimal usdcAmount; final Instant expiresAt;
        String status = "PENDING"; String transactionHash;
        Invoice(String id, List<Map<String, Object>> items, BigDecimal zarTotal, BigDecimal usdcAmount, Instant expiresAt) { this.id=id; this.items=items; this.zarTotal=zarTotal; this.usdcAmount=usdcAmount; this.expiresAt=expiresAt; }
        Map<String, Object> response() { Map<String,Object> r = new LinkedHashMap<>(); r.put("id",id); r.put("status",status); r.put("merchantAddress",merchantAddress); r.put("currency","USDC"); r.put("issuer",issuer); r.put("amount",usdcAmount.toPlainString()); r.put("zarTotal",zarTotal.toPlainString()); r.put("zarPerUsdc",zarPerUsdc.toPlainString()); r.put("expiresAt",expiresAt.toString()); r.put("transactionHash",transactionHash); r.put("paymentUri","xrpl:"+merchantAddress+"?amount="+usdcAmount.toPlainString()+"&currency="+currency+"&issuer="+issuer+"&memo="+id); return r; }
    }
}
