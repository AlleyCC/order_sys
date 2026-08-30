package com.example.orderSystem.security;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT 的身份驗證攔截器(design.md D9)。
 * 從匿名內部類抽成具名類:可單元測試,token 驗證細節(過期/拉黑/Redis 降級)
 * 委給 TokenAuthenticator,這裡只負責入口職責——讀 STOMP header、依結果 setUser 或拒連。
 * WS 層不做角色授權,身份(userId)只用於 user destination 路由。
 */
@Component
@RequiredArgsConstructor
public class WsAuthChannelInterceptor implements ChannelInterceptor {

    private final TokenAuthenticator tokenAuthenticator;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessageDeliveryException("未提供有效的 Authorization header");
        }

        Authentication auth = tokenAuthenticator.tryAuthenticate(header.substring(7))
                .orElseThrow(() -> new MessageDeliveryException("JWT 驗證失敗"));
        accessor.setUser(auth);
        return message;
    }
}
