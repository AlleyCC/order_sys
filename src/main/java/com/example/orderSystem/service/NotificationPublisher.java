package com.example.orderSystem.service;

import com.example.orderSystem.dto.websocket.NotificationEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPublisher {

    public static final String CHANNEL = "websocket:notifications";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(String userId, String destination, Object payload) {
        NotificationEnvelope envelope = new NotificationEnvelope(userId, destination, payload);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notification envelope for user={}, destination={}",
                    userId, destination, e);
        }
    }
}
