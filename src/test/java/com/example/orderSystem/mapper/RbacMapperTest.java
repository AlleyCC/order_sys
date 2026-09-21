package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.entity.Role;
import com.example.orderSystem.entity.RoleResource;
import com.example.orderSystem.entity.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC mapper 整合測試:驗證授權熱路徑的兩條 join query。
 * V9 種子資料前提:admin=SUPER_ADMIN、alice/bob/charlie 零角色、
 * category 三資源掛 ADMIN_STAFF、後台資源掛 CUSTOMER_SERVICE / ACCOUNTANT。
 *
 * @MybatisPlusTest 預設每個測試包在 transaction 裡結束後 rollback,
 * 所以測試內的 insert 不會互相汙染。
 */
@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RbacMapperTest {

    // 獨立於 AbstractIntegrationTest 的輕量容器:mapper 切片測試不需要 Redis / 金鑰
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paypool")
            .withUsername("root")
            .withPassword("test");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired RoleMapper roleMapper;
    @Autowired ResourceMapper resourceMapper;
    @Autowired UserRoleMapper userRoleMapper;
    @Autowired RoleResourceMapper roleResourceMapper;

    private Long roleIdOf(String name) {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("name", name));
        assertThat(role).as("種子角色 %s 應存在", name).isNotNull();
        return role.getRoleId();
    }

    private Long anyResourceId() {
        Resource resource = resourceMapper.selectOne(
                new QueryWrapper<Resource>().eq("url_pattern", "/category/create_category"));
        assertThat(resource).as("種子資源應存在").isNotNull();
        return resource.getResourceId();
    }

    // ---- selectRoleNamesByUserId ----

    @Test
    @DisplayName("查使用者角色:admin 搬遷後應為 SUPER_ADMIN")
    void selectRoleNames_migratedAdmin() {
        List<String> names = roleMapper.selectRoleNamesByUserId("admin");
        assertThat(names).containsExactly("SUPER_ADMIN");
    }

    @Test
    @DisplayName("查使用者角色:多角色帳號回傳全部角色")
    void selectRoleNames_multiRole() {
        grantRole("alice", roleIdOf("ADMIN_STAFF"));
        grantRole("alice", roleIdOf("ACCOUNTANT"));

        List<String> names = roleMapper.selectRoleNamesByUserId("alice");
        assertThat(names).containsExactlyInAnyOrder("ADMIN_STAFF", "ACCOUNTANT");
    }

    @Test
    @DisplayName("查使用者角色:停用中的角色(status=0)不回傳")
    void selectRoleNames_excludesDisabledRole() {
        Role disabled = new Role();
        disabled.setName("SUSPENDED_ROLE");
        disabled.setStatus(0);
        roleMapper.insert(disabled);

        grantRole("bob", disabled.getRoleId());
        grantRole("bob", roleIdOf("ADMIN_STAFF"));

        List<String> names = roleMapper.selectRoleNamesByUserId("bob");
        assertThat(names).containsExactly("ADMIN_STAFF");
    }

    @Test
    @DisplayName("查使用者角色:無任何角色回空清單")
    void selectRoleNames_userWithoutRoles() {
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", "charlie"));

        List<String> names = roleMapper.selectRoleNamesByUserId("charlie");
        assertThat(names).isEmpty();
    }

    // ---- selectResourcesByRoleName ----

    @Test
    @DisplayName("查角色資源:授權後回傳資源(含 pattern 與 method)")
    void selectResources_afterGrant() {
        Long freshRoleId = insertRole("GRANT_TEST_ROLE");

        RoleResource grant = new RoleResource();
        grant.setRoleId(freshRoleId);
        grant.setResourceId(anyResourceId());
        roleResourceMapper.insert(grant);

        List<Resource> resources = resourceMapper.selectResourcesByRoleName("GRANT_TEST_ROLE");
        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).getUrlPattern()).isEqualTo("/category/create_category");
        assertThat(resources.get(0).getHttpMethod()).isEqualTo("POST");
    }

    @Test
    @DisplayName("查角色資源:未授權任何資源回空清單")
    void selectResources_roleWithoutGrants() {
        insertRole("UNGRANTED_TEST_ROLE");

        List<Resource> resources = resourceMapper.selectResourcesByRoleName("UNGRANTED_TEST_ROLE");
        assertThat(resources).isEmpty();
    }

    private void grantRole(String userId, Long roleId) {
        UserRole ur = new UserRole();
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        userRoleMapper.insert(ur);
    }

    private Long insertRole(String name) {
        Role role = new Role();
        role.setName(name);
        roleMapper.insert(role);
        return role.getRoleId();
    }
}
