package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("stores")
public class Store {

    @TableId(type = IdType.INPUT)
    private String storeId;
    private String storeName;
    private String phone;
    private String address;
    private Integer minOrderAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
