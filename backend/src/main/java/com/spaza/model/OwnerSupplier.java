package com.spaza.model;

import jakarta.persistence.*;
import java.time.Instant;

/** A supplier store that a spaza owner has added to their own supplier list. */
@Entity
@Table(name = "owner_suppliers", uniqueConstraints = @UniqueConstraint(name = "uk_owner_supplier", columnNames = {"ownerId", "supplierId"}))
public class OwnerSupplier {
  @Id private String id;
  @Column(nullable = false) private String ownerId;
  @Column(nullable = false) private String supplierId;
  @Column(nullable = false) private String supplierName;
  @Column(nullable = false) private Instant addedAt;

  protected OwnerSupplier() {}
  public OwnerSupplier(String id, String ownerId, String supplierId, String supplierName) {
    this.id=id; this.ownerId=ownerId; this.supplierId=supplierId; this.supplierName=supplierName; this.addedAt=Instant.now();
  }
  public String getId(){return id;} public String getOwnerId(){return ownerId;} public String getSupplierId(){return supplierId;} public String getSupplierName(){return supplierName;} public Instant getAddedAt(){return addedAt;}
}
