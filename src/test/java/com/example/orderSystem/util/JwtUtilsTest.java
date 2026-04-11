package com.example.orderSystem.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class JwtUtilsTest {

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
    private JwtUtils jwtUtils;

    @Test
    @DisplayName("generateAccessToken 產出的 JWT 包含 jti claim")
    void tokenContainsJti() {
        String token = jwtUtils.generateAccessToken("alice", "employee");
        Claims claims = jwtUtils.parseToken(token);

        assertThat(claims.getId()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("每次產出的 jti 都不同")
    void jtiIsUnique() {
        String token1 = jwtUtils.generateAccessToken("alice", "employee");
        String token2 = jwtUtils.generateAccessToken("alice", "employee");

        String jti1 = jwtUtils.parseToken(token1).getId();
        String jti2 = jwtUtils.parseToken(token2).getId();

        assertThat(jti1).isNotEqualTo(jti2);
    }

    @Test
    @DisplayName("extractJti 回傳正確的 jti")
    void extractJti() {
        String token = jwtUtils.generateAccessToken("bob", "admin");
        Claims claims = jwtUtils.parseToken(token);

        assertThat(jwtUtils.extractJti(token)).isEqualTo(claims.getId());
    }

    @Test
    @DisplayName("getRemainingSeconds 回傳正數")
    void remainingSeconds() {
        String token = jwtUtils.generateAccessToken("alice", "employee");
        Claims claims = jwtUtils.parseToken(token);

        long remaining = jwtUtils.getRemainingSeconds(claims);
        assertThat(remaining).isGreaterThan(0).isLessThanOrEqualTo(900);
    }
}
