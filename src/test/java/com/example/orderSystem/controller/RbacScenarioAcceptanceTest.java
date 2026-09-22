package com.example.orderSystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.mapper.CategoryMapper;
import com.example.orderSystem.mapper.ResourceMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import com.example.orderSystem.util.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Cipher;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 行為規格驗收:情境 1–5(spec: rbac-authorization / rbac-management)。
 * 情境 6(Redis 故障降級)在 RbacRedisDegradationTest——它要停 Redis 容器,
 * 不能用這裡的共用容器。
 *
 * 種子前提:admin=SUPER_ADMIN、alice/bob 零角色、category 三資源掛在 ADMIN_STAFF。
 */
class RbacScenarioAcceptanceTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtils jwtUtils;
    @Autowired ObjectMapper objectMapper;
    @Autowired CategoryMapper categoryMapper;
    @Autowired ResourceMapper resourceMapper;

    private String adminToken() {
        return jwtUtils.generateAccessToken("admin");
    }

    @AfterEach
    void restoreRbacState() throws Exception {
        updateRoleResources("ADMIN_STAFF", seededCategoryResourceIds());
        updateUserRoles("bob", "");
    }

    private String seededCategoryResourceIds() {
        return resourceIdOf("/category/create_category")
                + "," + resourceIdOf("/category/update_category")
                + "," + resourceIdOf("/category/delete_category");
    }

    // ---- helpers ----

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

    private void updateRoleResources(String roleName, String resourceIdsCsv) throws Exception {
        mockMvc.perform(post("/admin/rbac/update_role_resources")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleName\":\"" + roleName + "\",\"resourceIds\":[" + resourceIdsCsv + "]}"))
                .andExpect(status().isOk());
    }

    private void updateUserRoles(String userId, String roleNamesCsv) throws Exception {
        mockMvc.perform(post("/admin/rbac/update_user_roles")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"roleNames\":[" + roleNamesCsv + "]}"))
                .andExpect(status().isOk());
    }

    private Long resourceIdOf(String pattern) {
        return resourceMapper.selectOne(
                new QueryWrapper<Resource>().eq("url_pattern", pattern)).getResourceId();
    }

    private long categoryCount() {
        return categoryMapper.selectCount(null);
    }

    // ---- 情境 1:登入成功 ----

    @Test
    @DisplayName("情境1:帳密正確 → 取得憑證,且該憑證可用於後續操作")
    void scenario1_loginIssuesUsableCredential() throws Exception {
        String resp = mockMvc.perform(post("/login/create_token")
                        .header("Authorization", "JWT " + encryptPassword("test1234"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(resp).get("accessToken").asText();

        // 憑證真的能證明身份:拿去呼叫受保護端點成功
        mockMvc.perform(get("/category/get_categories")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    // ---- 情境 2 + 3:有權限成功 / 無權限 403 且無資料異動 ----

    @Test
    @DisplayName("情境2:ADMIN_STAFF 被授權建立分類 → 操作成功")
    void scenario2_grantedOperationSucceeds() throws Exception {
        updateUserRoles("bob", "\"ADMIN_STAFF\"");
        updateRoleResources("ADMIN_STAFF", resourceIdOf("/category/create_category").toString());

        mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + jwtUtils.generateAccessToken("bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC情境2\",\"sortOrder\":91}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("情境3:零角色使用者未被授權 → 403「權限不足」(非 401),且無資料異動")
    void scenario3_deniedOperationIs403AndNoMutation() throws Exception {
        long before = categoryCount();

        mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + jwtUtils.generateAccessToken("alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC情境3\",\"sortOrder\":92}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("權限不足"));

        assertThat(categoryCount()).isEqualTo(before);
    }

    // ---- 情境 4:未登入 401,白名單仍可用 ----

    @Test
    @DisplayName("情境4:無憑證 → 401「未登入」;白名單(API 文件頁)不受影響")
    void scenario4_unauthenticatedIs401AndWhitelistOpen() throws Exception {
        // 無憑證 → 401,語意與 403 可區分
        mockMvc.perform(get("/category/get_categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("未登入或憑證已失效"));

        // 偽造/損毀憑證 → 一樣 401
        mockMvc.perform(get("/category/get_categories")
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/order/get_all_shops"))
                .andExpect(status().isUnauthorized());

        // 白名單:API 文件頁未登入仍成功
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    // ---- 情境 5:權限即時調整(不重啟、不重新登入) ----

    @Test
    @DisplayName("情境5:調整角色權限與使用者角色後,同一顆 token 下一個請求即套用")
    void scenario5_permissionChangesTakeEffectImmediately() throws Exception {
        updateUserRoles("bob", "\"ADMIN_STAFF\"");
        updateRoleResources("ADMIN_STAFF", "");
        String bobToken = jwtUtils.generateAccessToken("bob");   // 全程用同一顆 token
        String createCategoryId = resourceIdOf("/category/create_category").toString();

        // (a) ADMIN_STAFF 尚未被授權 → 403
        mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC情境5a\",\"sortOrder\":93}"))
                .andExpect(status().isForbidden());

        // (b) 管理員把「建立分類」加進 ADMIN_STAFF → 同一顆 token 立即可用
        updateRoleResources("ADMIN_STAFF", createCategoryId);
        mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC情境5b\",\"sortOrder\":94}"))
                .andExpect(status().isCreated());

        // (c) 管理員移除授權 → 同一顆 token 立即被擋
        updateRoleResources("ADMIN_STAFF", "");
        mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC情境5c\",\"sortOrder\":95}"))
                .andExpect(status().isForbidden());

        // (d) 重新授權 ADMIN_STAFF,但把 bob 的角色拔光 → 立即失效
        updateRoleResources("ADMIN_STAFF", createCategoryId);
        updateUserRoles("bob", "");
        mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC情境5d\",\"sortOrder\":96}"))
                .andExpect(status().isForbidden());
    }

    // ---- 零角色:一般使用者的常態 ----

    @Test
    @DisplayName("零角色使用者:未登記端點放行,已登記端點 403")
    void zeroRoleUserPassesUnmanagedAndIsDeniedOnManaged() throws Exception {
        updateUserRoles("bob", "");
        String bobToken = jwtUtils.generateAccessToken("bob");

        mockMvc.perform(get("/category/get_categories")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC情境零角色\",\"sortOrder\":97}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("權限不足"));
    }

    // ---- 收尾清理:刪除本測試建立的分類,避免污染其他測試的查詢 ----

    @AfterEach
    void cleanCreatedCategories() {
        categoryMapper.delete(new QueryWrapper<Category>().likeRight("name", "RBAC情境"));
    }
}
