package com.example.orderSystem.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisConnectionDetails connection, RedisProperties redisProperties,
                                         LoginLimitProperties loginLimitProperties) {
        if (connection.getSentinel() != null || connection.getCluster() != null) {
            throw new IllegalStateException("登入限流的 Redisson 只支援單機 Redis,sentinel / cluster 需調整 RedissonConfig");
        }
        RedisConnectionDetails.Standalone standalone = connection.getStandalone();

        Config config = new Config();
        config.setLazyInitialization(true);
        SingleServerConfig server = config.useSingleServer()
                .setAddress(scheme(redisProperties, standalone) + standalone.getHost() + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase());
        if (StringUtils.hasText(connection.getUsername())) {
            server.setUsername(connection.getUsername());
        }
        if (StringUtils.hasText(connection.getPassword())) {
            server.setPassword(connection.getPassword());
        }
        server.setTimeout((int) loginLimitProperties.getRedisTimeout().toMillis())
                .setConnectTimeout((int) loginLimitProperties.getRedisConnectTimeout().toMillis())
                .setRetryAttempts(0);
        return Redisson.create(config);
    }

    private String scheme(RedisProperties redisProperties, RedisConnectionDetails.Standalone standalone) {
        boolean urlUsesTls = redisProperties.getUrl() != null && redisProperties.getUrl().startsWith("rediss://");
        boolean tls = urlUsesTls || redisProperties.getSsl().isEnabled() || standalone.getSslBundle() != null;
        return tls ? "rediss://" : "redis://";
    }
}
