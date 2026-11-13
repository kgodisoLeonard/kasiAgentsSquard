package com.spaza.model;

import lombok.Data;

@Data
public class Product {
    private String id;
    private String name;
    private String barcode;
    private String category;
    private int qty;
    private double cost;
    private double price;
    private int lowStock;
}
