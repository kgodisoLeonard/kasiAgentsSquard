package com.spaza.model;

import lombok.Data;
import java.util.List;

@Data
public class SaleRequest {
    private List<SaleItem> items;
    private String paymentMethod;
    private Double cashTendered;
}
