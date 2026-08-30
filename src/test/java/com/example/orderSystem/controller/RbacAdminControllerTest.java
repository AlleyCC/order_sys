package com.example.orderSystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.entity.Role;
import com.example.orderSystem.entity.RoleResource;
import com.example.orderSystem.entity.UserRole;
import com.example.orderSystem.mapper.ResourceMapper;
import com.example.orderSystem.mapper.RoleMapper;
import com.example.orderSystem.mapper.RoleResourceMapper;
import com.example.orderSystem.mapper.UserRoleMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import com.example.orderSystem.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 管理端整合測試(spec: rbac-management)。
 * 保護機制:V7 已把 /admin/rbac/** 登記進 resources 且不掛角色,
 * 所以「僅超管可用」不靠註解,靠動態授權層 + 超管不變量。
 *
 * 種子前提:admin=SUPER_ADMIN、alice/bob/charlie=MEMBER、
 * category 三資源 + /admin/rbac/** 資源、role_resources 為空。
 */
class RbacAdminControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtils jwtUtils;
    @Autowired RoleMapper roleMapper;
    @Autowired ResourceMapper resourceMapper;
    @Autowired RoleResourceMapper roleResourceMapper;
    @Autowired UserRoleMapper userRoleMapper;

    private String adminToken;
    private String memberToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtUtils.generateAccessToken("admin");
        memberToken = jwtUtils.generateAccessToken("alice");
    }

    private Long roleId(String name) {
        return roleMapper.selectOne(new QueryWrapper<Role>().eq("name", name)).getRoleId();
    }

    private Long resourceId(String pattern) {
        return resourceMapper.selectOne(new QueryWrapper<Resource>().eq("url_pattern", pattern)).getResourceId();
    }

    @Nested
    @DisplayName("查詢端點(僅超管)")
    class Queries {

        @Test
        @DisplayName("超管查角色清單 → 200,含四個種子角色")
        void listRoles() throws Exception {
            mockMvc.perform(get("/admin/rbac/get_roles")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name", hasItems(
                            "SUPER_ADMIN", "LEADER", "CUSTOMER_SERVICE", "MEMBER")));
        }

        @Test
        @DisplayName("超管查資源清單 → 200,含種子資源")
        void listResources() throws Exception {
            mockMvc.perform(get("/admin/rbac/get_resources")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].urlPattern", hasItems(
                            "/category/create_category", "/admin/rbac/**")));
        }

        @Test
        @DisplayName("超管查某角色的資源 → 200(CUSTOMER_SERVICE 起始為空)")
        void listRoleResources() throws Exception {
            mockMvc.perform(get("/admin/rbac/get_role_resources")
                            .param("roleName", "CUSTOMER_SERVICE")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isEmpty());
        }

        @Test
        @DisplayName("查不存在的角色 → 404")
        void listRoleResources_unknownRole() throws Exception {
            mockMvc.perform(get("/admin/rbac/get_role_resources")
                            .param("roleName", "NO_SUCH_ROLE")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非超管查角色清單 → 403(動態授權層擋下)")
        void nonAdminForbidden() throws Exception {
            mockMvc.perform(get("/admin/rbac/get_roles")
                            .header("Authorization", "Bearer " + memberToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("權限不足"));
        }
    }

    @Nested
    @DisplayName("POST /admin/rbac/update_role_resources")
    class UpdateRoleResources {

        @Test
        @DisplayName("超管全量替換角色資源 → 200,DB 生效且可查回")
        void adminReplacesRoleResources() throws Exception {
            Long categoryCreate = resourceId("/category/create_category");

            mockMvc.perform(post("/admin/rbac/update_role_resources")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roleName\":\"LEADER\",\"resourceIds\":[" + categoryCreate + "]}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/admin/rbac/get_role_resources")
                            .param("roleName", "LEADER")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].urlPattern", hasItem("/category/create_category")));
        }

        @Test
        @DisplayName("非超管調整 → 403,且 role_resources 無資料異動")
        void nonAdminForbidden_noMutation() throws Exception {
            Long categoryDelete = resourceId("/category/delete_category");
            long before = roleResourceMapper.selectCount(
                    new QueryWrapper<RoleResource>().eq("role_id", roleId("CUSTOMER_SERVICE")));

            mockMvc.perform(post("/admin/rbac/update_role_resources")
                            .header("Authorization", "Bearer " + memberToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roleName\":\"CUSTOMER_SERVICE\",\"resourceIds\":[" + categoryDelete + "]}"))
                    .andExpect(status().isForbidden());

            long after = roleResourceMapper.selectCount(
                    new QueryWrapper<RoleResource>().eq("role_id", roleId("CUSTOMER_SERVICE")));
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("不存在的 resourceId → 400,整批不寫入")
        void unknownResourceId_rejected() throws Exception {
            mockMvc.perform(post("/admin/rbac/update_role_resources")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"roleName\":\"CUSTOMER_SERVICE\",\"resourceIds\":[999999]}"))
                    .andExpect(status().isBadRequest());

            long count = roleResourceMapper.selectCount(
                    new QueryWrapper<RoleResource>().eq("role_id", roleId("CUSTOMER_SERVICE")));
            assertThat(count).isZero();
        }
    }

    @Nested
    @DisplayName("POST /admin/rbac/update_user_roles")
    class UpdateUserRoles {

        @Test
        @DisplayName("超管全量替換使用者角色 → 200,user_roles 生效")
        void adminReplacesUserRoles() throws Exception {
            mockMvc.perform(post("/admin/rbac/update_user_roles")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"bob\",\"roleNames\":[\"MEMBER\",\"LEADER\"]}"))
                    .andExpect(status().isOk());

            List<UserRole> bobRoles = userRoleMapper.selectList(
                    new QueryWrapper<UserRole>().eq("user_id", "bob"));
            assertThat(bobRoles).hasSize(2);
        }

        @Test
        @DisplayName("不存在的使用者 → 404")
        void unknownUser_rejected() throws Exception {
            mockMvc.perform(post("/admin/rbac/update_user_roles")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"ghost\",\"roleNames\":[\"MEMBER\"]}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("不存在的角色名 → 400,user_roles 不變")
        void unknownRoleName_rejected() throws Exception {
            long before = userRoleMapper.selectCount(
                    new QueryWrapper<UserRole>().eq("user_id", "charlie"));

            mockMvc.perform(post("/admin/rbac/update_user_roles")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"charlie\",\"roleNames\":[\"NO_SUCH_ROLE\"]}"))
                    .andExpect(status().isBadRequest());

            long after = userRoleMapper.selectCount(
                    new QueryWrapper<UserRole>().eq("user_id", "charlie"));
            assertThat(after).isEqualTo(before);
        }
    }
}
