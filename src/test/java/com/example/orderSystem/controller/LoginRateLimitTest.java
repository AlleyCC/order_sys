package com.example.orderSystem.controller;

import com.example.orderSystem.config.LoginLimitProperties;
import com.example.orderSystem.service.LoginRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "app.login-limit.ip.rate=5",
        "app.login-limit.ip.interval=1m",
        "app.login-limit.user-ip.rate=5",
        "app.login-limit.user-ip.interval=15m",
        "app.login-limit.user.rate=20",
        "app.login-limit.user.interval=1h"
})
class LoginRateLimitTest extends AbstractLoginRateLimitTest {

    @Autowired
    private RedissonClient redissonClient;

    private LoginRateLimiter limiterWithIpRate(long rate) {
        LoginLimitProperties properties = new LoginLimitProperties();
        properties.setIp(new LoginLimitProperties.Limit(rate, Duration.ofMinutes(1)));
        return new LoginRateLimiter(redissonClient, properties);
    }

    @Test
    @DisplayName("調高門檻後立即生效,不受舊設定留在 Redis 的 key 影響")
    void changedLimitTakesEffectImmediately() {
        String ip = "10.1.0.9";
        LoginRateLimiter strict = limiterWithIpRate(1);
        assertThat(strict.tryAttempt(randomUser(), ip)).isTrue();
        assertThat(strict.tryAttempt(randomUser(), ip)).isFalse();

        LoginRateLimiter relaxed = limiterWithIpRate(5);

        assertThat(relaxed.tryAttempt(randomUser(), ip)).isTrue();
    }

    @Test
    @DisplayName("同一 IP 第 6 次登入請求回 401,即使帳密正確")
    void sixthAttemptFromSameIpIsRejected(CapturedOutput output) throws Exception {
        String ip = "10.1.0.1";
        for (int i = 0; i < 5; i++) {
            login(randomUser(), WRONG_PASSWORD, ip).andExpect(status().isUnauthorized());
        }

        login("alice", CORRECT_PASSWORD, ip)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("帳密錯誤"));

        assertThat(output).contains("layer=IP account=alice ip=10.1.0.1");
    }

    @Test
    @DisplayName("IP A 達上限後,IP B 以正確帳密登入成功")
    void otherIpIsNotAffected() throws Exception {
        for (int i = 0; i < 6; i++) {
            login(randomUser(), WRONG_PASSWORD, "10.1.0.2");
        }

        login("alice", CORRECT_PASSWORD, "10.1.0.3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("每次帶不同 X-Forwarded-For,同一連線來源仍被拒")
    void forwardedForIsIgnored() throws Exception {
        String ip = "10.1.0.4";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(loginRequest(randomUser(), WRONG_PASSWORD, ip)
                            .header("X-Forwarded-For", "203.0.113." + i))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginRequest("alice", CORRECT_PASSWORD, ip)
                        .header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("被限流的回應與帳密錯誤的回應無法區分")
    void limitedResponseLooksLikeWrongPassword() throws Exception {
        MockHttpServletResponse wrongPassword = login("alice", WRONG_PASSWORD, "10.1.0.5")
                .andReturn().getResponse();

        String ip = "10.1.0.6";
        for (int i = 0; i < 5; i++) {
            login(randomUser(), WRONG_PASSWORD, ip);
        }
        MockHttpServletResponse limited = login("alice", CORRECT_PASSWORD, ip)
                .andReturn().getResponse();

        assertThat(limited.getStatus()).isEqualTo(wrongPassword.getStatus());
        assertThat(limited.getContentAsString()).isEqualTo(wrongPassword.getContentAsString());
        assertThat(limited.getHeaderNames()).isEqualTo(wrongPassword.getHeaderNames());
        assertThat(limited.getHeader("Retry-After")).isNull();
    }
}
