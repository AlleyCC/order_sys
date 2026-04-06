package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_items")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Integer itemId;
    private String orderId;
    private String userId;
    private Integer menuId;
    private String productName;
    private Integer unitPrice;
    private Integer quantity;
    private Integer subtotal;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
