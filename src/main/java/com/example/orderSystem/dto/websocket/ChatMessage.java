package com.example.orderSystem.dto.websocket;

import lombok.Data;

@Data
public class ChatMessage {

    private final String type = "CHAT";
    private String orderId;
    private String userId;
    private String userName;
    private String message;
    private String timestamp;
}
