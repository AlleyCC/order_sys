package com.example.orderSystem.security;

import com.example.orderSystem.service.TokenRedisService;
import com.example.orderSystem.util.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 「token 字串 → 已認證身份」的唯一實作(design.md D9)。
 * HTTP(JwtAuthenticationFilter)與 WS(WsAuthChannelInterceptor)兩個入口共用,
 * token 契約變更只需要改這裡。
 *
 * 契約:
 * - 成功 → Authentication(principal = userId,authorities 留空:
 *   「能做什麼」由 DynamicAuthorizationManager 每次請求查,不放身份層)
 * - 過期 / 簽章無效 / 已拉黑 → Optional.empty(),不區分原因(呼叫端不需要)
 * - 「header 有沒有帶 token、Bearer 前綴」是入口層職責,這裡只收純 token 字串
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenAuthenticator {

    private final JwtUtils jwtUtils;
    private final TokenRedisService tokenRedisService;

    public Optional<Authentication> tryAuthenticate(String token) {
        Claims claims;
        try {
            claims = jwtUtils.parseToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            // 過期、簽章無效、格式錯誤——一律視為未認證
            return Optional.empty();
        }

        String jti = claims.getId();
        if (jti != null && isBlacklistedFailOpen(jti)) {
            // 已登出撤銷
            return Optional.empty();
        }

        return Optional.of(
                UsernamePasswordAuthenticationToken.authenticated(claims.getSubject(), null, List.of()));
    }

    /**
     * 黑名單檢查的降級策略:Redis 故障時 fail-open(當作未被拉黑),
     * 認證功能不因快取故障而中斷(spec: 快取故障時授權降級不中斷)。
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
