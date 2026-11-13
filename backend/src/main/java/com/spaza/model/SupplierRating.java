package com.spaza.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "supplier_ratings", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_order_rating", columnNames = {"supplierId", "orderId"}))
public class SupplierRating {
  @Id private String id;
  @Column(nullable = false) private String supplierId;
  @Column(nullable = false) private String supplierName;
  @Column(nullable = false) private String ownerId;
  @Column(nullable = false) private String ownerName;
  @Column(nullable = false) private String orderId;
  @Column(nullable = false) private int rating;
  @Column(length = 500) private String comment;
  @Column(nullable = false) private Instant createdAt;

  protected SupplierRating() {}
  public SupplierRating(String id, String supplierId, String supplierName, String ownerId, String ownerName, String orderId, int rating, String comment) {
    this.id=id; this.supplierId=supplierId; this.supplierName=supplierName; this.ownerId=ownerId; this.ownerName=ownerName; this.orderId=orderId; this.rating=rating; this.comment=comment; this.createdAt=Instant.now();
  }
  public String getId(){return id;} public String getSupplierId(){return supplierId;} public String getSupplierName(){return supplierName;} public String getOwnerId(){return ownerId;} public String getOwnerName(){return ownerName;} public String getOrderId(){return orderId;} public int getRating(){return rating;} public String getComment(){return comment;} public Instant getCreatedAt(){return createdAt;}
}
