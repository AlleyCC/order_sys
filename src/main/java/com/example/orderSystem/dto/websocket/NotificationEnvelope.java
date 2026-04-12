package com.example.orderSystem.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEnvelope {
    private String userId;
    private String destination;
    private Object payload;
}
