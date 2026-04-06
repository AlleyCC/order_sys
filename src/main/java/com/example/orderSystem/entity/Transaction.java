package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.orderSystem.enums.TradeType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("transactions")
public class Transaction {

    @TableId(type = IdType.INPUT)
    private String transactionId;
    private String userId;
    private String orderId;
    private Integer amount;
    private Integer closingBalance;
    private TradeType type;
    private LocalDateTime createdAt;
    private String createdBy;
}
