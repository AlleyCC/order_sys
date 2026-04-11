package com.example.orderSystem.config;

import com.example.orderSystem.service.TokenRedisService;
import com.example.orderSystem.util.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtils jwtUtils;
    private final TokenRedisService tokenRedisService;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/user/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String token = accessor.getFirstNativeHeader("Authorization");
                    if (token == null || !token.startsWith("Bearer ")) {
                        throw new MessageDeliveryException("未提供有效的 Authorization header");
                    }
                    try {
                        Claims claims = jwtUtils.parseToken(token.substring(7));
                        String jti = claims.getId();
                        if (jti != null && tokenRedisService.isAccessTokenBlacklisted(jti)) {
                            throw new MessageDeliveryException("Token 已被登出");
                        }
                        String userId = claims.getSubject();
                        String role = claims.get("role", String.class);
                        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                        var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                        accessor.setUser(auth);
                    } catch (MessageDeliveryException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new MessageDeliveryException("JWT 驗證失敗");
                    }
                }
                return message;
            }
        });
    }
}
