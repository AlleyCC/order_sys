package com.example.orderSystem.service;

import com.example.orderSystem.config.LoginLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

    @Mock
    private RedissonClient redissonClient;

    private LoginRateLimiter limiter() {
        return new LoginRateLimiter(redissonClient, new LoginLimitProperties());
    }

    @Test
    @DisplayName("Redisson 已關閉等非 RedisException 的錯誤,也 fail-open 放行")
    void failsOpenOnAnyRuntimeException() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new IllegalStateException("Redisson is shutdown"));

        assertThat(limiter().tryAttempt("alice", "10.0.0.1")).isTrue();
    }

    @Test
    @DisplayName("登入成功後更新限流紀錄失敗,不影響登入")
    void onSuccessSwallowsAnyRuntimeException() {
        when(redissonClient.getRateLimiter(anyString()))
                .thenThrow(new IllegalStateException("Redisson is shutdown"));

        assertThatCode(() -> limiter().onSuccess("alice", "10.0.0.1")).doesNotThrowAnyException();
    }
}
