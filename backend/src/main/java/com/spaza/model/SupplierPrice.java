package com.spaza.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** One line of a supplier's price list: the wholesale price per stock unit of one product. */
@Entity
@Table(name = "supplier_prices", indexes = @Index(name = "ix_supplier_prices_supplier", columnList = "supplierId"),
    uniqueConstraints = @UniqueConstraint(name = "uk_supplier_price_product", columnNames = {"supplierId", "product"}))
public class SupplierPrice {
  @Id private String id;
  @Column(nullable = false) private String supplierId;
  @Column(nullable = false) private String product;
  @Column(nullable = false, precision = 12, scale = 2) private BigDecimal unitPrice;
  /** Units available; null = not tracked. */
  private Integer stock;
  @Column(nullable = false) private Instant updatedAt;

  protected SupplierPrice() {}
  public SupplierPrice(String supplierId, String product, BigDecimal unitPrice, Integer stock) {
    this.id = key(supplierId, product); this.supplierId = supplierId; this.product = product; this.unitPrice = unitPrice; this.stock = stock; this.updatedAt = Instant.now();
  }
  public Integer getStock() { return stock; }
  public static String key(String supplierId, String product) { return supplierId + "|" + product.trim().toLowerCase(java.util.Locale.ROOT); }
  public String getSupplierId() { return supplierId; } public String getProduct() { return product; }
  public BigDecimal getUnitPrice() { return unitPrice; } public Instant getUpdatedAt() { return updatedAt; }
}
