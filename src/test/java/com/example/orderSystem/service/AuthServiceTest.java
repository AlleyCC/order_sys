package com.example.orderSystem.service;

import com.example.orderSystem.dto.request.LoginRequest;
import com.example.orderSystem.dto.request.RefreshRequest;
import com.example.orderSystem.dto.response.LoginResponse;
import com.example.orderSystem.dto.response.RefreshResponse;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.exception.AuthenticationException;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.util.JwtUtils;
import com.example.orderSystem.util.PasswordUtils;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private PasswordUtils passwordUtils;

    @Mock
    private TokenRedisService tokenRedisService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "accessTokenExpiresIn", 900L);
        ReflectionTestUtils.setField(authService, "refreshTokenDays", 7L);
    }

    private User createUser(String userId, String rawPassword) {
        User user = new User();
        user.setUserId(userId);
        user.setUserName(userId);
        user.setPassword(encoder.encode(rawPassword));
        user.setRole("employee");
        return user;
    }

    private LoginRequest loginRequest(String userId) {
        LoginRequest req = new LoginRequest();
        req.setUserId(userId);
        return req;
    }

    private RefreshRequest refreshRequest(String tokenId) {
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken(tokenId);
        return req;
    }

    // ========== Login ==========

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("成功 → 回傳 accessToken + refreshToken + expiresIn, 並存入 Redis")
        void success() throws Exception {
            User user = createUser("alice", "test1234");
            when(passwordUtils.decryptPassword("encrypted")).thenReturn("test1234");
            when(userMapper.selectById("alice")).thenReturn(user);
            when(jwtUtils.generateAccessToken("alice", "employee")).thenReturn("jwt-token");

            LoginResponse resp = authService.login(loginRequest("alice"), "encrypted");

            assertThat(resp.getAccessToken()).isEqualTo("jwt-token");
            assertThat(resp.getRefreshToken()).isNotBlank();
            assertThat(resp.getExpiresIn()).isEqualTo(900);
            verify(tokenRedisService).saveRefreshToken(anyString(), eq("alice"), eq(604800L));
        }

        @Test
        @DisplayName("RSA 解密失敗 → AuthenticationException")
        void rsaDecryptFails() throws Exception {
            when(passwordUtils.decryptPassword("bad")).thenThrow(new RuntimeException("decrypt error"));

            assertThatThrownBy(() -> authService.login(loginRequest("alice"), "bad"))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("帳密錯誤");
        }

        @Test
        @DisplayName("使用者不存在 → AuthenticationException")
        void userNotFound() throws Exception {
            when(passwordUtils.decryptPassword("encrypted")).thenReturn("test1234");
            when(userMapper.selectById("nobody")).thenReturn(null);

            assertThatThrownBy(() -> authService.login(loginRequest("nobody"), "encrypted"))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("帳密錯誤");
        }

        @Test
        @DisplayName("密碼不匹配 → AuthenticationException")
        void wrongPassword() throws Exception {
            User user = createUser("alice", "correct");
            when(passwordUtils.decryptPassword("encrypted")).thenReturn("wrong");
            when(userMapper.selectById("alice")).thenReturn(user);

            assertThatThrownBy(() -> authService.login(loginRequest("alice"), "encrypted"))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("帳密錯誤");
        }
    }

    // ========== Refresh ==========

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("成功 → 回傳新 accessToken")
        void success() {
            User user = createUser("alice", "pw");
            when(tokenRedisService.getRefreshTokenUserId("rt-001")).thenReturn("alice");
            when(userMapper.selectById("alice")).thenReturn(user);
            when(jwtUtils.generateAccessToken("alice", "employee")).thenReturn("new-jwt");

            RefreshResponse resp = authService.refresh(refreshRequest("rt-001"));

            assertThat(resp.getAccessToken()).isEqualTo("new-jwt");
            assertThat(resp.getExpiresIn()).isEqualTo(900);
        }

        @Test
        @DisplayName("token 不存在（Redis 回傳 null）→ AuthenticationException")
        void tokenNotFound() {
            when(tokenRedisService.getRefreshTokenUserId("bad")).thenReturn(null);

            assertThatThrownBy(() -> authService.refresh(refreshRequest("bad")))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("Refresh Token 已失效");
        }

        @Test
        @DisplayName("user 不存在（被刪除）→ AuthenticationException")
        void userDeleted() {
            when(tokenRedisService.getRefreshTokenUserId("rt-001")).thenReturn("deleted-user");
            when(userMapper.selectById("deleted-user")).thenReturn(null);

            assertThatThrownBy(() -> authService.refresh(refreshRequest("rt-001")))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("Refresh Token 已失效");
        }
    }

    // ========== Logout ==========

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("成功 → blacklist access token + 刪除 refresh token")
        void success() {
            Claims claims = mock(Claims.class);
            when(claims.getId()).thenReturn("jti-123");
            when(jwtUtils.parseToken("access-token")).thenReturn(claims);
            when(jwtUtils.getRemainingSeconds(claims)).thenReturn(600L);

            authService.logout("rt-001", "access-token");

            verify(tokenRedisService).blacklistAccessToken("jti-123", 600L);
            verify(tokenRedisService).deleteRefreshToken("rt-001");
        }

        @Test
        @DisplayName("access token 已過期 → 不拋錯，仍刪除 refresh token")
        void expiredAccessToken() {
            when(jwtUtils.parseToken("expired-token")).thenThrow(new RuntimeException("expired"));

            assertThatCode(() -> authService.logout("rt-001", "expired-token"))
                    .doesNotThrowAnyException();
            verify(tokenRedisService).deleteRefreshToken("rt-001");
        }

        @Test
        @DisplayName("access token 為 null → 只刪除 refresh token")
        void nullAccessToken() {
            authService.logout("rt-001", null);

            verify(tokenRedisService, never()).blacklistAccessToken(anyString(), anyLong());
            verify(tokenRedisService).deleteRefreshToken("rt-001");
        }

        @Test
        @DisplayName("refresh token 為 null → 只 blacklist access token")
        void nullRefreshToken() {
            Claims claims = mock(Claims.class);
            when(claims.getId()).thenReturn("jti-456");
            when(jwtUtils.parseToken("access-token")).thenReturn(claims);
            when(jwtUtils.getRemainingSeconds(claims)).thenReturn(300L);

            authService.logout(null, "access-token");

            verify(tokenRedisService).blacklistAccessToken("jti-456", 300L);
            verify(tokenRedisService, never()).deleteRefreshToken(anyString());
        }
    }
}
