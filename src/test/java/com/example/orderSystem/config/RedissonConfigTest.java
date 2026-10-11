package com.example.orderSystem.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedissonConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(LoginLimitProperties.class, RedissonConfig.class);

    private SingleServerConfig serverConfig(RedissonClient client) {
        return client.getConfig().useSingleServer();
    }

    @Test
    @DisplayName("host / port / database / password 設定照用")
    void usesHostAndPort() {
        runner.withPropertyValues(
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.port=6390",
                        "spring.data.redis.database=3",
                        "spring.data.redis.password=secret")
                .run(context -> {
                    SingleServerConfig server = serverConfig(context.getBean(RedissonClient.class));
                    assertThat(server.getAddress()).isEqualTo("redis://redis.internal:6390");
                    assertThat(server.getDatabase()).isEqualTo(3);
                    assertThat(server.getPassword()).isEqualTo("secret");
                });
    }

    @Test
    @DisplayName("spring.data.redis.url 優先於 host / port,並帶入帳號、密碼、database 與 TLS")
    void usesUrlWithCredentialsAndTls() {
        runner.withPropertyValues(
                        "spring.data.redis.host=ignored-host",
                        "spring.data.redis.url=rediss://app-user:pw@redis.prod:6380/2")
                .run(context -> {
                    SingleServerConfig server = serverConfig(context.getBean(RedissonClient.class));
                    assertThat(server.getAddress()).isEqualTo("rediss://redis.prod:6380");
                    assertThat(server.getUsername()).isEqualTo("app-user");
                    assertThat(server.getPassword()).isEqualTo("pw");
                    assertThat(server.getDatabase()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("spring.data.redis.ssl.enabled=true 時使用 rediss")
    void usesTlsWhenSslEnabled() {
        runner.withPropertyValues(
                        "spring.data.redis.host=redis.internal",
                        "spring.data.redis.ssl.enabled=true")
                .run(context -> assertThat(serverConfig(context.getBean(RedissonClient.class)).getAddress())
                        .isEqualTo("rediss://redis.internal:6379"));
    }

    @Test
    @DisplayName("Redis 變慢時要快速放棄:預設指令逾時 300ms、連線逾時 2s、不重試")
    void failsFastByDefault() {
        runner.withPropertyValues("spring.data.redis.timeout=60s")
                .run(context -> {
                    SingleServerConfig server = serverConfig(context.getBean(RedissonClient.class));
                    assertThat(server.getTimeout()).isEqualTo(300);
                    assertThat(server.getConnectTimeout()).isEqualTo(2000);
                    assertThat(server.getRetryAttempts()).isZero();
                });
    }

    @Test
    @DisplayName("逾時可由 app.login-limit.redis-timeout 調整")
    void timeoutIsConfigurable() {
        runner.withPropertyValues("app.login-limit.redis-timeout=1s")
                .run(context -> assertThat(serverConfig(context.getBean(RedissonClient.class)).getTimeout())
                        .isEqualTo(1000));
    }

    @Test
    @DisplayName("連線逾時可由 app.login-limit.redis-connect-timeout 調整,不影響指令逾時")
    void connectTimeoutIsConfigurable() {
        runner.withPropertyValues("app.login-limit.redis-connect-timeout=5s")
                .run(context -> {
                    SingleServerConfig server = serverConfig(context.getBean(RedissonClient.class));
                    assertThat(server.getConnectTimeout()).isEqualTo(5000);
                    assertThat(server.getTimeout()).isEqualTo(300);
                });
    }

    @Test
    @DisplayName("只做登入限流,連線池與執行緒維持小規模")
    void keepsPoolsSmall() {
        runner.run(context -> {
            RedissonClient client = context.getBean(RedissonClient.class);
            SingleServerConfig server = serverConfig(client);
            assertThat(server.getConnectionPoolSize()).isEqualTo(8);
            assertThat(server.getConnectionMinimumIdleSize()).isEqualTo(1);
            assertThat(client.getConfig().getThreads()).isEqualTo(2);
            assertThat(client.getConfig().getNettyThreads()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("設定了 SSL bundle 時啟動失敗:Redisson 不會套用 bundle 的憑證,不默默用錯的信任來源")
    void failsFastOnSslBundle() {
        RedisProperties redisProperties = new RedisProperties();
        redisProperties.getSsl().setBundle("internal-ca");
        RedisConnectionDetails connection = new RedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of("redis.internal", 6379);
            }
        };

        assertThatThrownBy(() -> RedissonConfig.redissonConfig(connection, redisProperties, new LoginLimitProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SSL bundle");
    }

    @Test
    @DisplayName("設定了 sentinel 時啟動失敗,不默默連到別台")
    void failsFastOnSentinel() {
        runner.withPropertyValues(
                        "spring.data.redis.sentinel.master=mymaster",
                        "spring.data.redis.sentinel.nodes=sentinel-1:26379")
                .run(context -> assertThat(context).hasFailed());
    }
}
