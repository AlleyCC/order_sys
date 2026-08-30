package com.example.orderSystem.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 入口的身份驗證:只負責讀 Authorization header,
 * token 驗證細節(過期/拉黑/Redis 降級)委給 TokenAuthenticator(與 WS 入口共用)。
 * 驗證失敗不設身份,讓後續的 401 entry point 處理。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenAuthenticator tokenAuthenticator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            tokenAuthenticator.tryAuthenticate(header.substring(7))
                    .ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
        }

        filterChain.doFilter(request, response);
    }
}
