package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.orderSystem.enums.RechargeStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("recharge_orders")
public class RechargeOrder {

    @TableId(type = IdType.INPUT)
    private String rechargeId;
    private String userId;
    private Integer amount;
    private RechargeStatus status;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
