package com.example.orderSystem.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class TokenRedisServiceTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paypool")
            .withUsername("root")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TokenRedisService tokenRedisService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanRedis() {
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Nested
    @DisplayName("Access Token Blacklist")
    class Blacklist {

        @Test
        @DisplayName("blacklist 後 isBlacklisted 回傳 true")
        void blacklistAndCheck() {
            tokenRedisService.blacklistAccessToken("jti-123", 60);
            assertThat(tokenRedisService.isAccessTokenBlacklisted("jti-123")).isTrue();
        }

        @Test
        @DisplayName("未 blacklist 的 jti 回傳 false")
        void notBlacklisted() {
            assertThat(tokenRedisService.isAccessTokenBlacklisted("jti-not-exist")).isFalse();
        }

        @Test
        @DisplayName("remainingSeconds <= 0 不存入 Redis")
        void zeroTtlNotStored() {
            tokenRedisService.blacklistAccessToken("jti-expired", 0);
            assertThat(tokenRedisService.isAccessTokenBlacklisted("jti-expired")).isFalse();
        }

        @Test
        @DisplayName("blacklist key 有 TTL")
        void blacklistHasTtl() {
            tokenRedisService.blacklistAccessToken("jti-ttl", 300);
            Long ttl = redisTemplate.getExpire("blacklist:jti-ttl", TimeUnit.SECONDS);
            assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(300);
        }
    }

    @Nested
    @DisplayName("Refresh Token")
    class RefreshToken {

        @Test
        @DisplayName("save 後 get 回傳 userId")
        void saveAndGet() {
            tokenRedisService.saveRefreshToken("rt-001", "alice", 600);
            assertThat(tokenRedisService.getRefreshTokenUserId("rt-001")).isEqualTo("alice");
        }

        @Test
        @DisplayName("不存在的 token 回傳 null")
        void getNotExist() {
            assertThat(tokenRedisService.getRefreshTokenUserId("rt-not-exist")).isNull();
        }

        @Test
        @DisplayName("delete 後 get 回傳 null")
        void deleteToken() {
            tokenRedisService.saveRefreshToken("rt-del", "bob", 600);
            tokenRedisService.deleteRefreshToken("rt-del");
            assertThat(tokenRedisService.getRefreshTokenUserId("rt-del")).isNull();
        }

        @Test
        @DisplayName("refresh token 有 TTL")
        void refreshTokenHasTtl() {
            tokenRedisService.saveRefreshToken("rt-ttl", "charlie", 3600);
            Long ttl = redisTemplate.getExpire("refresh:rt-ttl", TimeUnit.SECONDS);
            assertThat(ttl).isNotNull().isGreaterThan(0).isLessThanOrEqualTo(3600);
        }
    }
}
