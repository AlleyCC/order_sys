package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.orderSystem.enums.NotificationType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notifications")
public class Notification {

    @TableId(type = IdType.INPUT)
    private String notificationId;
    private String userId;
    private String orderId;
    private NotificationType type;
    private String content;
    private String dedupKey;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
