package com.spaza.finance;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** An owner's spending mandate. Only changed through MandateService.update by an authenticated human owner. */
@Entity
@Table(name = "spending_mandates")
public class SpendingMandate {
  @Id private String ownerId;
  @Column(nullable = false, precision = 14, scale = 2) private BigDecimal perOrderCap;
  @Column(nullable = false, precision = 14, scale = 2) private BigDecimal dailyCap;
  /** Comma-separated supplier ids on the approved allow-list. */
  @Column(nullable = false, length = 2000) private String allowedSuppliers;
  /** Deadline rule 1: no payments after this date. */
  @Column(nullable = false) private LocalDate validUntil;
  /** Deadline rule 2: a payment for an order is only allowed before that order's approval deadline. */
  @Column(nullable = false) private boolean enforceOrderDeadline;
  @Column(nullable = false) private long version;
  @Column(nullable = false) private String updatedBy;
  @Column(nullable = false) private Instant updatedAt;

  protected SpendingMandate() {}
  public SpendingMandate(String ownerId, BigDecimal perOrderCap, BigDecimal dailyCap, Collection<String> allowedSuppliers, LocalDate validUntil, boolean enforceOrderDeadline, String updatedBy, Instant at) {
    this.ownerId = ownerId; this.version = 0;
    apply(perOrderCap, dailyCap, allowedSuppliers, validUntil, enforceOrderDeadline, updatedBy, at);
  }
  void apply(BigDecimal perOrderCap, BigDecimal dailyCap, Collection<String> allowedSuppliers, LocalDate validUntil, boolean enforceOrderDeadline, String updatedBy, Instant at) {
    this.perOrderCap = perOrderCap; this.dailyCap = dailyCap;
    this.allowedSuppliers = String.join(",", new LinkedHashSet<>(allowedSuppliers));
    this.validUntil = validUntil; this.enforceOrderDeadline = enforceOrderDeadline;
    this.updatedBy = updatedBy; this.updatedAt = at; this.version++;
  }
  public String getOwnerId() { return ownerId; }
  public BigDecimal getPerOrderCap() { return perOrderCap; }
  public BigDecimal getDailyCap() { return dailyCap; }
  public List<String> getAllowedSuppliers() { return allowedSuppliers.isBlank() ? List.of() : List.of(allowedSuppliers.split(",")); }
  public LocalDate getValidUntil() { return validUntil; }
  public boolean isEnforceOrderDeadline() { return enforceOrderDeadline; }
  public long getVersion() { return version; }
  public String getUpdatedBy() { return updatedBy; }
  public Instant getUpdatedAt() { return updatedAt; }
}
