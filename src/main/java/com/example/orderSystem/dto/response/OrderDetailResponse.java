package com.example.orderSystem.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDetailResponse {

    private String orderId;
    private String orderName;
    private Integer minOrderAmount;
    private LocalDateTime deadline;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private List<OrderItemResponse> orderItems;
}
