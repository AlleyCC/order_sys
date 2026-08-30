package com.example.orderSystem.controller;

import com.example.orderSystem.util.JwtUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 情境 6:快取服務故障,授權功能降級但不中斷(spec: rbac-authorization)。
 *
 * 刻意「不」繼承 AbstractIntegrationTest:測試中會把 Redis 容器整個停掉,
 * 共用容器會污染同 JVM 的其他整合測試,所以這個 class 用自己的一組容器與 context。
 * 啟動時 Redis 正常(context 內的 pub/sub 訂閱等元件正常初始化),
 * 測試中才模擬故障。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RbacRedisDegradationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paypool")
            .withUsername("root")
            .withPassword("test");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static final Path KEY_DIR;

    static {
        MYSQL.start();
        REDIS.start();
        KEY_DIR = generateEphemeralKeys();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Redis 停掉後連線要快速失敗,測試才不會等 timeout
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
        registry.add("spring.data.redis.timeout", () -> "500ms");

        String priv = KEY_DIR.resolve("private.pem").toString();
        String pub = KEY_DIR.resolve("public.pem").toString();
        registry.add("app.jwt.private-key", () -> priv);
        registry.add("app.jwt.public-key", () -> pub);
        registry.add("app.password.private-key", () -> priv);
        registry.add("app.password.public-key", () -> pub);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtils jwtUtils;

    @Test
    @DisplayName("情境6:Redis 停止後,已登入且有權限的操作仍成功(降級直查 DB)")
    void scenario6_redisDown_authorizedRequestStillSucceeds() throws Exception {
        // 已登入使用者(admin=SUPER_ADMIN,正常情況必定成功的操作)
        String token = jwtUtils.generateAccessToken("admin");

        // 先確認 Redis 正常時能通(基準)
        mockMvc.perform(get("/category/get_categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 快取機制故障
        REDIS.stop();

        // 操作仍然成功:黑名單檢查 fail-open、權限查詢 fallback DB
        mockMvc.perform(get("/category/get_categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 未登入的請求在降級狀態下依然被正確擋下(降級 ≠ 大門敞開)
        mockMvc.perform(get("/category/get_categories"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 丟棄式金鑰(同 AbstractIntegrationTest 的做法) ----

    private static Path generateEphemeralKeys() {
        try {
            Path dir = Files.createTempDirectory("degradation-keys");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            Files.writeString(dir.resolve("private.pem"), pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
            Files.writeString(dir.resolve("public.pem"), pem("PUBLIC KEY", kp.getPublic().getEncoded()));
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException("無法生成測試金鑰", e);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
