package com.example.orderSystem.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "app.login-limit")
public class LoginLimitProperties {

    private Limit ip = new Limit(5, Duration.ofMinutes(1));
    private Limit userIp = new Limit(5, Duration.ofMinutes(15));
    private Limit user = new Limit(20, Duration.ofHours(1));
    private Duration trustedIpTtl = Duration.ofDays(30);
    private Duration redisTimeout = Duration.ofMillis(300);
    private Duration redisConnectTimeout = Duration.ofSeconds(2);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Limit {
        private long rate;
        private Duration interval;
    }
}
