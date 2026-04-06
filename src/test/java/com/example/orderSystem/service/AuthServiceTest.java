package com.example.orderSystem.service;

import com.example.orderSystem.dto.request.LoginRequest;
import com.example.orderSystem.dto.request.RefreshRequest;
import com.example.orderSystem.dto.response.LoginResponse;
import com.example.orderSystem.dto.response.RefreshResponse;
import com.example.orderSystem.entity.RefreshToken;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.exception.AuthenticationException;
import com.example.orderSystem.mapper.RefreshTokenMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.util.JwtUtils;
import com.example.orderSystem.util.PasswordUtils;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private RefreshTokenMapper refreshTokenMapper;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private PasswordUtils passwordUtils;

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

    private RefreshToken createRefreshToken(String tokenId, String userId, boolean revoked, LocalDateTime expireTime) {
        RefreshToken rt = new RefreshToken();
        rt.setTokenId(tokenId);
        rt.setUserId(userId);
        rt.setRevoked(revoked);
        rt.setExpireTime(expireTime);
        return rt;
    }

    // ========== Login ==========

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("成功 → 回傳 accessToken + refreshToken + expiresIn")
        void success() throws Exception {
            User user = createUser("alice", "test1234");
            when(passwordUtils.decryptPassword("encrypted")).thenReturn("test1234");
            when(userMapper.selectById("alice")).thenReturn(user);
            when(jwtUtils.generateAccessToken("alice", "employee")).thenReturn("jwt-token");
            when(refreshTokenMapper.insert((RefreshToken) any())).thenReturn(1);

            LoginResponse resp = authService.login(loginRequest("alice"), "encrypted");

            assertThat(resp.getAccessToken()).isEqualTo("jwt-token");
            assertThat(resp.getRefreshToken()).isNotBlank();
            assertThat(resp.getExpiresIn()).isEqualTo(900);
            verify(refreshTokenMapper).insert((RefreshToken) any());
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
            RefreshToken rt = createRefreshToken("rt-001", "alice", false, LocalDateTime.now().plusDays(1));
            User user = createUser("alice", "pw");
            when(refreshTokenMapper.selectById("rt-001")).thenReturn(rt);
            when(userMapper.selectById("alice")).thenReturn(user);
            when(jwtUtils.generateAccessToken("alice", "employee")).thenReturn("new-jwt");

            RefreshResponse resp = authService.refresh(refreshRequest("rt-001"));

            assertThat(resp.getAccessToken()).isEqualTo("new-jwt");
            assertThat(resp.getExpiresIn()).isEqualTo(900);
        }

        @Test
        @DisplayName("token 不存在 → AuthenticationException")
        void tokenNotFound() {
            when(refreshTokenMapper.selectById("bad")).thenReturn(null);

            assertThatThrownBy(() -> authService.refresh(refreshRequest("bad")))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("Refresh Token 已失效");
        }

        @Test
        @DisplayName("token 已 revoke → AuthenticationException")
        void tokenRevoked() {
            RefreshToken rt = createRefreshToken("rt-001", "alice", true, LocalDateTime.now().plusDays(1));
            when(refreshTokenMapper.selectById("rt-001")).thenReturn(rt);

            assertThatThrownBy(() -> authService.refresh(refreshRequest("rt-001")))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("Refresh Token 已失效");
        }

        @Test
        @DisplayName("token 已過期 → AuthenticationException")
        void tokenExpired() {
            RefreshToken rt = createRefreshToken("rt-001", "alice", false, LocalDateTime.now().minusDays(1));
            when(refreshTokenMapper.selectById("rt-001")).thenReturn(rt);

            assertThatThrownBy(() -> authService.refresh(refreshRequest("rt-001")))
                    .isInstanceOf(AuthenticationException.class)
                    .hasMessage("Refresh Token 已失效");
        }

        @Test
        @DisplayName("user 不存在（被刪除）→ AuthenticationException")
        void userDeleted() {
            RefreshToken rt = createRefreshToken("rt-001", "deleted-user", false, LocalDateTime.now().plusDays(1));
            when(refreshTokenMapper.selectById("rt-001")).thenReturn(rt);
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
        @DisplayName("成功 → revoked 設為 true")
        void success() {
            RefreshToken rt = createRefreshToken("rt-001", "alice", false, LocalDateTime.now().plusDays(1));
            when(refreshTokenMapper.selectById("rt-001")).thenReturn(rt);
            when(refreshTokenMapper.updateById((RefreshToken) any())).thenReturn(1);

            authService.logout("rt-001");

            assertThat(rt.getRevoked()).isTrue();
            verify(refreshTokenMapper).updateById((RefreshToken) any());
        }

        @Test
        @DisplayName("token 不存在 → 不拋錯（靜默處理）")
        void tokenNotFound() {
            when(refreshTokenMapper.selectById("bad")).thenReturn(null);

            assertThatCode(() -> authService.logout("bad"))
                    .doesNotThrowAnyException();
            verify(refreshTokenMapper, never()).updateById((RefreshToken) any());
        }

        @Test
        @DisplayName("token 已 revoke → 不重複更新")
        void alreadyRevoked() {
            RefreshToken rt = createRefreshToken("rt-001", "alice", true, LocalDateTime.now().plusDays(1));
            when(refreshTokenMapper.selectById("rt-001")).thenReturn(rt);

            authService.logout("rt-001");

            verify(refreshTokenMapper, never()).updateById((RefreshToken) any());
        }
    }
}
