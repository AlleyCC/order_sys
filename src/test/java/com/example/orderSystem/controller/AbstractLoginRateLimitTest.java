package com.example.orderSystem.controller;

import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Cipher;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(OutputCaptureExtension.class)
abstract class AbstractLoginRateLimitTest extends AbstractIntegrationTest {

    protected static final String CORRECT_PASSWORD = "test1234";
    protected static final String WRONG_PASSWORD = "wrong-password";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearLoginLimitKeys() {
        Set<String> keys = loginLimitKeys();
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    protected Set<String> loginLimitKeys() {
        Set<String> keys = redisTemplate.keys("*login:*");
        return keys == null ? Set.of() : keys;
    }

    protected ResultActions login(String userId, String password, String ip) throws Exception {
        return mockMvc.perform(loginRequest(userId, password, ip));
    }

    protected MockHttpServletRequestBuilder loginRequest(String userId, String password, String ip) throws Exception {
        return post("/login/create_token")
                .header("Authorization", "JWT " + encryptPassword(password))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}")
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
    }

    protected String randomUser() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String encryptPassword(String plaintext) throws Exception {
        String content = Files.readString(KEY_DIR.resolve("public.pem"));
        String base64 = content
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes()));
    }
}
