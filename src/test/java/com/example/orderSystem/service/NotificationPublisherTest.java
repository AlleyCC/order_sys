package com.example.orderSystem.service;

import com.example.orderSystem.dto.websocket.BalanceMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private NotificationPublisher publisher;

    @Test
    @DisplayName("publish → 發送到 websocket:notifications channel，內容為 envelope JSON")
    void publishesEnvelopeJson() throws Exception {
        BalanceMessage msg = new BalanceMessage(4500, "下單");

        publisher.publish("alice", "/queue/balance", msg);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("websocket:notifications"), jsonCaptor.capture());

        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"userId\":\"alice\"");
        assertThat(json).contains("\"destination\":\"/queue/balance\"");
        assertThat(json).contains("\"availableBalance\":4500");
        assertThat(json).contains("\"reason\":\"下單\"");
    }
}
