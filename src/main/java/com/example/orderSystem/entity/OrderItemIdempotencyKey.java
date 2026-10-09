package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("order_item_idempotency_keys")
public class OrderItemIdempotencyKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String idempotencyKey;
    private Integer itemId;
    private LocalDateTime createdAt;
}
