package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.INPUT)
    private String userId;
    private String userName;
    private String password;
    private String role;
    private Long balance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
