package com.example.orderSystem.controller;

import com.example.orderSystem.dto.response.LoginResponse;
import com.example.orderSystem.util.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Cipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AuthControllerTest {

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    private String encryptPassword(String plaintext) throws Exception {
        String content = Files.readString(Path.of("./key/password_public.key"));
        String base64 = content
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes()));
    }

    // ========== Login ==========

    @Nested
    @DisplayName("POST /login/create_token")
    class Login {

        @Test
        @DisplayName("正確帳號密碼 → 200, 回傳 accessToken + refreshToken")
        void loginSuccess() throws Exception {
            String encrypted = encryptPassword("test1234");

            mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "JWT " + encrypted)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"alice\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.expiresIn").value(900));
        }

        @Test
        @DisplayName("密碼錯誤 → 401")
        void loginWrongPassword() throws Exception {
            String encrypted = encryptPassword("wrongpassword");

            mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "JWT " + encrypted)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"alice\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("帳密錯誤"));
        }

        @Test
        @DisplayName("帳號不存在 → 401 (不揭露帳號是否存在)")
        void loginUserNotFound() throws Exception {
            String encrypted = encryptPassword("test1234");

            mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "JWT " + encrypted)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"nonexistent\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("帳密錯誤"));
        }

        @Test
        @DisplayName("Authorization Header 格式錯誤 → 401")
        void loginBadAuthHeader() throws Exception {
            mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "Bearer something")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"alice\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("缺少 userId → 400")
        void loginMissingUserId() throws Exception {
            String encrypted = encryptPassword("test1234");

            mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "JWT " + encrypted)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== Refresh ==========

    @Nested
    @DisplayName("POST /auth/refresh")
    class Refresh {

        private LoginResponse doLogin() throws Exception {
            String encrypted = encryptPassword("test1234");
            MvcResult result = mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "JWT " + encrypted)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"alice\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
        }

        @Test
        @DisplayName("有效的 refreshToken → 200, 回傳新 accessToken")
        void refreshSuccess() throws Exception {
            LoginResponse login = doLogin();

            mockMvc.perform(post("/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + login.getRefreshToken() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.expiresIn").value(900));
        }

        @Test
        @DisplayName("不存在的 refreshToken → 401")
        void refreshInvalidToken() throws Exception {
            mockMvc.perform(post("/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"nonexistent-token-id\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Refresh Token 已失效"));
        }
    }

    // ========== Logout ==========

    @Nested
    @DisplayName("POST /login/logout")
    class Logout {

        private LoginResponse doLogin() throws Exception {
            String encrypted = encryptPassword("test1234");
            MvcResult result = mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "JWT " + encrypted)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"alice\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
        }

        @Test
        @DisplayName("登出 → 200, 之後 refresh 失敗")
        void logoutThenRefreshFails() throws Exception {
            LoginResponse login = doLogin();

            // Logout
            mockMvc.perform(post("/login/logout")
                            .header("Authorization", "Bearer " + login.getAccessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + login.getRefreshToken() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("登出成功"));

            // Refresh should fail
            mockMvc.perform(post("/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + login.getRefreshToken() + "\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("登出 → access token 立即失效（blacklist）")
        void logoutThenAccessTokenBlacklisted() throws Exception {
            LoginResponse login = doLogin();

            // Access token works before logout
            mockMvc.perform(get("/user/get_user_transaction_record")
                            .header("Authorization", "Bearer " + login.getAccessToken()))
                    .andExpect(status().is(not(401)));

            // Logout
            mockMvc.perform(post("/login/logout")
                            .header("Authorization", "Bearer " + login.getAccessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + login.getRefreshToken() + "\"}"))
                    .andExpect(status().isOk());

            // Access token should be rejected after logout
            mockMvc.perform(get("/user/get_user_transaction_record")
                            .header("Authorization", "Bearer " + login.getAccessToken()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========== JWT Filter ==========

    @Nested
    @DisplayName("JWT Authentication Filter")
    class JwtFilter {

        @Test
        @DisplayName("無 Token 存取受保護 API → 401")
        void noToken() throws Exception {
            mockMvc.perform(get("/user/get_user_transaction_record"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("有效 Token 存取受保護 API → 不是 401")
        void validToken() throws Exception {
            String token = jwtUtils.generateAccessToken("alice", "employee");

            mockMvc.perform(get("/user/get_user_transaction_record")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().is(not(401)));
        }

        @Test
        @DisplayName("無效 Token → 401")
        void invalidToken() throws Exception {
            mockMvc.perform(get("/user/get_user_transaction_record")
                            .header("Authorization", "Bearer invalid.jwt.token"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
