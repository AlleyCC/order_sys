package com.example.orderSystem.service;

import com.example.orderSystem.dto.websocket.NotificationEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisWebSocketRelay implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            NotificationEnvelope envelope = objectMapper.readValue(json, NotificationEnvelope.class);
            messagingTemplate.convertAndSendToUser(
                    envelope.getUserId(), envelope.getDestination(), envelope.getPayload());
        } catch (Exception e) {
            log.warn("Failed to relay notification from Redis: {}", e.getMessage());
        }
    }
}
