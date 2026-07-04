package com.example.orderSystem.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * 所有整合測試的共用基底:啟動 MySQL / Redis 容器、生成丟棄式 RSA 金鑰、
 * 把連線與金鑰路徑注入 Spring context。整合測試只要 extends 這個類別,
 * 就不用再重複這些設定,也不依賴本機的 ./key/。
 *
 * 容器採 singleton 模式:static 啟動一次,整個 JVM 內所有整合測試共用
 * (Testcontainers 的 Ryuk 會在測試結束後自動回收),比每個 class 各起一組快得多。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

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

        String priv = KEY_DIR.resolve("private.pem").toString();
        String pub = KEY_DIR.resolve("public.pem").toString();
        registry.add("app.jwt.private-key", () -> priv);
        registry.add("app.jwt.public-key", () -> pub);
        registry.add("app.password.private-key", () -> priv);   // 測試複用同一把即可
        registry.add("app.password.public-key", () -> pub);
    }

    // ---- 丟棄式測試金鑰:私鑰 getEncoded()=PKCS#8、公鑰=X.509,對上 JwtUtils/PasswordUtils ----

    private static Path generateEphemeralKeys() {
        try {
            Path dir = Files.createTempDirectory("it-keys");
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
