package com.example.orderSystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisWebSocketRelayTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RedisWebSocketRelay relay;

    @Test
    @DisplayName("收到 envelope JSON → 呼叫 convertAndSendToUser 轉發")
    void relaysEnvelope() {
        String json = """
                {"userId":"alice","destination":"/queue/balance",
                 "payload":{"type":"BALANCE_UPDATED","availableBalance":4500,"reason":"下單"}}
                """;

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(json.getBytes());

        relay.onMessage(message, null);

        verify(messagingTemplate).convertAndSendToUser(eq("alice"), eq("/queue/balance"), any());
    }

    @Test
    @DisplayName("格式錯誤的 JSON → 不拋例外，不呼叫 messagingTemplate")
    void invalidJsonSwallowed() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("not-a-json".getBytes());

        assertThatCode(() -> relay.onMessage(message, null)).doesNotThrowAnyException();
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }
}
