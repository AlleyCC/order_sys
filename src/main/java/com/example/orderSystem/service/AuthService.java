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
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public LoginResponse login(LoginRequest request, String encryptedPassword) {
        // 1. RSA decrypt password
        String rawPassword;
        try {
            rawPassword = passwordUtils.decryptPassword(encryptedPassword);
        } catch (Exception e) {
            throw new AuthenticationException("帳密錯誤");
        }

        // 2. Find user
        User user = userMapper.selectById(request.getUserId());
        if (user == null) {
            throw new AuthenticationException("帳密錯誤");
        }

        // 3. BCrypt verify
        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new AuthenticationException("帳密錯誤");
        }

        // 4. Generate tokens
        String accessToken = jwtUtils.generateAccessToken(user.getUserId(), user.getRole());
        String refreshTokenId = UUID.randomUUID().toString();

        // 5. Save refresh token to Redis
        long ttlSeconds = refreshTokenDays * 24 * 60 * 60;
        tokenRedisService.saveRefreshToken(refreshTokenId, user.getUserId(), ttlSeconds);

        return new LoginResponse(accessToken, refreshTokenId, accessTokenExpiresIn);
    }

    public RefreshResponse refresh(RefreshRequest request) {
        // 1. Find refresh token in Redis
        String userId = tokenRedisService.getRefreshTokenUserId(request.getRefreshToken());
        if (userId == null) {
            throw new AuthenticationException("Refresh Token 已失效");
        }

        // 2. Find user
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AuthenticationException("Refresh Token 已失效");
        }

        // 3. Generate new access token
        String accessToken = jwtUtils.generateAccessToken(user.getUserId(), user.getRole());

        return new RefreshResponse(accessToken, accessTokenExpiresIn);
    }

    public void logout(String refreshTokenId, String accessToken) {
        // Blacklist access token in Redis
        if (accessToken != null) {
            try {
                Claims claims = jwtUtils.parseToken(accessToken);
                String jti = claims.getId();
                if (jti != null) {
                    long remaining = jwtUtils.getRemainingSeconds(claims);
                    tokenRedisService.blacklistAccessToken(jti, remaining);
                }
            } catch (Exception e) {
                // Access token may already be expired — ignore
            }
        }

        // Delete refresh token from Redis
        if (refreshTokenId != null) {
            tokenRedisService.deleteRefreshToken(refreshTokenId);
        }
    }
}
