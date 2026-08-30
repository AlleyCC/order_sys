package com.example.orderSystem.security;

import com.example.orderSystem.service.TokenRedisService;
import com.example.orderSystem.util.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final TokenRedisService tokenRedisService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtils.parseToken(token);
                String jti = claims.getId();

                if (jti != null && isBlacklistedFailOpen(jti)) {
                    // Token has been revoked via logout
                    filterChain.doFilter(request, response);
                    return;
                }

                String userId = claims.getSubject();

                // Token 是純身份憑證:只證明「你是誰」。authorities 刻意留空——
                // 「你能做什麼」由 DynamicAuthorizationManager 每次請求查(Redis→DB),
                // 權限調整才能即時生效,不受 token 15 分鐘存活期影響。
                var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (ExpiredJwtException e) {
                // Token expired — don't set authentication, let Spring Security handle 401
            } catch (JwtException e) {
                // Invalid token — don't set authentication
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 黑名單檢查的降級策略:Redis 故障時 fail-open(當作未被拉黑),
     * 登入/授權功能不因快取故障而中斷(spec: 快取故障時授權降級不中斷)。
     * 風險有限:token 仍受簽章與 15 分鐘效期雙重保護,
     * 最壞情況是「已登出的 token 在 Redis 故障期間內仍可用到自然過期」。
     */
    private boolean isBlacklistedFailOpen(String jti) {
        try {
            return tokenRedisService.isAccessTokenBlacklisted(jti);
        } catch (Exception e) {
            log.warn("黑名單檢查失敗(Redis 故障?),fail-open 放行 jti={}", jti, e);
            return false;
        }
    }
}
