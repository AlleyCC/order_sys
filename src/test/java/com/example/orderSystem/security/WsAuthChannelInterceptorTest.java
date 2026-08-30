package com.example.orderSystem.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WS 攔截器單元測試(design.md D9,決策二選項 A):
 * 攔截器抽成具名類後,不用拉 Spring context 就能直接餵 STOMP frame 測。
 * token 驗證細節(過期/拉黑/降級)在 TokenAuthenticatorTest,這裡只測入口職責:
 * 讀 header、轉交、依結果 setUser 或拒連。
 */
@ExtendWith(MockitoExtension.class)
class WsAuthChannelInterceptorTest {

    @Mock TokenAuthenticator tokenAuthenticator;

    @InjectMocks WsAuthChannelInterceptor interceptor;

    private static StompHeaderAccessor connectAccessor(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.addNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private static Message<byte[]> messageOf(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("CONNECT 帶有效 token → setUser 成功")
    void connectWithValidToken_setsUser() {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());
        when(tokenAuthenticator.tryAuthenticate("good-token")).thenReturn(Optional.of(auth));

        StompHeaderAccessor accessor = connectAccessor("Bearer good-token");
        interceptor.preSend(messageOf(accessor), null);

        assertThat(accessor.getUser()).isSameAs(auth);
    }

    @Test
    @DisplayName("CONNECT 無 Authorization header → 拒連")
    void connectWithoutHeader_rejected() {
        StompHeaderAccessor accessor = connectAccessor(null);

        assertThatThrownBy(() -> interceptor.preSend(messageOf(accessor), null))
                .isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(tokenAuthenticator);
    }

    @Test
    @DisplayName("CONNECT header 非 Bearer 格式 → 拒連")
    void connectWithNonBearerHeader_rejected() {
        StompHeaderAccessor accessor = connectAccessor("Basic xxx");

        assertThatThrownBy(() -> interceptor.preSend(messageOf(accessor), null))
                .isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(tokenAuthenticator);
    }

    @Test
    @DisplayName("CONNECT 認證失敗(empty)→ 拒連")
    void connectWithInvalidToken_rejected() {
        when(tokenAuthenticator.tryAuthenticate("bad-token")).thenReturn(Optional.empty());

        StompHeaderAccessor accessor = connectAccessor("Bearer bad-token");

        assertThatThrownBy(() -> interceptor.preSend(messageOf(accessor), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("非 CONNECT frame(如 SEND)→ 原樣放行,不驗證")
    void nonConnectFrame_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = messageOf(accessor);

        Message<?> result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
        verifyNoInteractions(tokenAuthenticator);
    }
}
