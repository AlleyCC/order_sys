package com.example.orderSystem.security;

import com.example.orderSystem.service.TokenRedisService;
import com.example.orderSystem.util.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.core.Authentication;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * TokenAuthenticator 單元測試(design.md D9):
 * 「token 字串 → 已認證身份」的共用轉換,HTTP filter 與 WS 攔截器共用。
 * 契約:成功回 Optional<Authentication>(principal=userId、無 authorities);
 * 任何失敗(過期/無效/已拉黑)一律 empty,呼叫端不需要失敗原因;
 * 黑名單檢查 Redis 故障時 fail-open(視為未拉黑)。
 */
@ExtendWith(MockitoExtension.class)
class TokenAuthenticatorTest {

    @Mock JwtUtils jwtUtils;
    @Mock TokenRedisService tokenRedisService;

    @InjectMocks TokenAuthenticator authenticator;

    private static Claims claims(String userId, String jti) {
        return Jwts.claims().id(jti).subject(userId).build();
    }

    @Test
    @DisplayName("有效 token → Authentication(principal=userId,無 authorities)")
    void validToken_authenticated() {
        when(jwtUtils.parseToken("good")).thenReturn(claims("alice", "jti-1"));
        when(tokenRedisService.isAccessTokenBlacklisted("jti-1")).thenReturn(false);

        Optional<Authentication> result = authenticator.tryAuthenticate("good");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("alice");
        assertThat(result.get().isAuthenticated()).isTrue();
        assertThat(result.get().getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("過期 token → empty")
    void expiredToken_empty() {
        when(jwtUtils.parseToken("expired"))
                .thenThrow(new ExpiredJwtException(null, null, "expired"));

        assertThat(authenticator.tryAuthenticate("expired")).isEmpty();
    }

    @Test
    @DisplayName("簽章無效/格式錯誤 → empty")
    void malformedToken_empty() {
        when(jwtUtils.parseToken("garbage"))
                .thenThrow(new MalformedJwtException("bad"));

        assertThat(authenticator.tryAuthenticate("garbage")).isEmpty();
    }

    @Test
    @DisplayName("已被拉黑(登出)→ empty")
    void blacklistedToken_empty() {
        when(jwtUtils.parseToken("revoked")).thenReturn(claims("alice", "jti-2"));
        when(tokenRedisService.isAccessTokenBlacklisted("jti-2")).thenReturn(true);

        assertThat(authenticator.tryAuthenticate("revoked")).isEmpty();
    }

    @Test
    @DisplayName("黑名單檢查 Redis 炸掉 → fail-open,仍回 Authentication")
    void redisDown_failOpen() {
        when(jwtUtils.parseToken("good")).thenReturn(claims("alice", "jti-3"));
        when(tokenRedisService.isAccessTokenBlacklisted("jti-3"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        Optional<Authentication> result = authenticator.tryAuthenticate("good");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("alice");
    }
}
