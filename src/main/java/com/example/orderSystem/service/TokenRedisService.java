package com.example.orderSystem.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenRedisService {

    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String REFRESH_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;

    public void blacklistAccessToken(String jti, long remainingSeconds) {
        if (remainingSeconds > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + jti, "", remainingSeconds, TimeUnit.SECONDS);
        }
    }

    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }

    public void saveRefreshToken(String tokenId, String userId, long ttlSeconds) {
        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + tokenId, userId, ttlSeconds, TimeUnit.SECONDS);
    }

    public String getRefreshTokenUserId(String tokenId) {
        return redisTemplate.opsForValue().get(REFRESH_PREFIX + tokenId);
    }

    public void deleteRefreshToken(String tokenId) {
        redisTemplate.delete(REFRESH_PREFIX + tokenId);
    }
}
