package com.spaza.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

/** A stock line in ONE shop. Every product belongs to exactly one owner; owners never share product records. */
@Data
@Entity
@Table(name = "products",
    indexes = @Index(name = "ix_products_owner", columnList = "ownerId"),
    uniqueConstraints = @UniqueConstraint(name = "uk_products_owner_name", columnNames = {"ownerId", "name"}))
public class Product {
    @Id private String id;
    @JsonIgnore @Column(nullable = false) private String ownerId;
    @Column(nullable = false) private String name;
    private String barcode;
    private String category;
    private int qty;
    private double cost;
    private double price;
    private int lowStock;
}
