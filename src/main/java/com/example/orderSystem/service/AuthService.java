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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    @Value("${app.token.access-expires-seconds}")
    private long accessTokenExpiresIn;

    @Value("${app.token.refresh-expires-days}")
    private long refreshTokenDays;

    private final UserMapper userMapper;
    private final JwtUtils jwtUtils;
    private final PasswordUtils passwordUtils;
    private final TokenRedisService tokenRedisService;
    private final LoginRateLimiter loginRateLimiter;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginResponse login(LoginRequest request, String encryptedPassword, String clientIp) {
        if (!loginRateLimiter.tryAttempt(request.getUserId(), clientIp)) {
            throw new AuthenticationException("帳密錯誤");
        }

        String rawPassword;
        try {
            rawPassword = passwordUtils.decryptPassword(encryptedPassword);
        } catch (Exception e) {
            throw new AuthenticationException("帳密錯誤");
        }

        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new AuthenticationException("帳密錯誤");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new AuthenticationException("帳密錯誤");
        }

        loginRateLimiter.onSuccess(request.getUserId(), clientIp);

        String accessToken = jwtUtils.generateAccessToken(user.getUserId());
        String refreshTokenId = UUID.randomUUID().toString();

        long ttlSeconds = refreshTokenDays * 24 * 60 * 60;
        tokenRedisService.saveRefreshToken(refreshTokenId, user.getUserId(), ttlSeconds);

        return new LoginResponse(accessToken, refreshTokenId, accessTokenExpiresIn);
    }

    public RefreshResponse refresh(RefreshRequest request) {
        String userId = tokenRedisService.getRefreshTokenUserId(request.getRefreshToken());
        if (userId == null) {
            throw new AuthenticationException("Refresh Token 已失效");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AuthenticationException("Refresh Token 已失效");
        }

        String accessToken = jwtUtils.generateAccessToken(user.getUserId());

        return new RefreshResponse(accessToken, accessTokenExpiresIn);
    }

    public void logout(String refreshTokenId, String accessToken) {
        if (accessToken != null) {
            try {
                Claims claims = jwtUtils.parseToken(accessToken);
                String jti = claims.getId();
                if (jti != null) {
                    long remaining = jwtUtils.getRemainingSeconds(claims);
                    tokenRedisService.blacklistAccessToken(jti, remaining);
                }
            } catch (Exception ignored) {
            }
        }

        if (refreshTokenId != null) {
            tokenRedisService.deleteRefreshToken(refreshTokenId);
        }
    }
}
