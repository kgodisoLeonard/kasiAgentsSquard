package com.spaza.model;

import lombok.Data;
import java.util.List;

@Data
public class SaleRequest {
    private List<SaleItem> items;
    private String paymentMethod;
    private Double cashTendered;
    /** Required when paymentMethod is usdc. The invoice is verified on XRPL before stock is released. */
    private String usdcInvoiceId;
}
