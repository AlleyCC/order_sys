package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("refresh_tokens")
public class RefreshToken {

    @TableId(type = IdType.INPUT)
    private String tokenId;
    private String userId;
    private Boolean revoked;
    private LocalDateTime expireTime;
    private LocalDateTime createdAt;
}
