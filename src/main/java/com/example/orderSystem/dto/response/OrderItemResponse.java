package com.example.orderSystem.dto.response;

import lombok.Data;

@Data
public class OrderItemResponse {

    private Integer itemId;
    private String userId;
    private String userName;
    private String productName;
    private Integer unitPrice;
    private Integer quantity;
    private Integer subtotal;
}
