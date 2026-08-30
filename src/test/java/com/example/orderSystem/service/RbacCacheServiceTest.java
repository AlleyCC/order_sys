package com.example.orderSystem.service;

import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.mapper.ResourceMapper;
import com.example.orderSystem.mapper.RoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RbacCacheService 單元測試:Cache-Aside 讀寫 + Redis 故障降級。
 * Redis 與 mapper 全 mock,只驗證快取邏輯本身。
 *
 * Key 規格(design.md D3):
 *   rbac:user-roles:{userId}      → JSON array of role names
 *   rbac:role-resources:{roleName} → JSON array of Resource
 */
@ExtendWith(MockitoExtension.class)
class RbacCacheServiceTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock RoleMapper roleMapper;
    @Mock ResourceMapper resourceMapper;

    RbacCacheService service;

    @BeforeEach
    void setUp() {
        // getUserRoles/getRoleResources 都會經過 opsForValue();個別測試不一定每條路徑都走到,用 lenient
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new RbacCacheService(redisTemplate, roleMapper, resourceMapper, new ObjectMapper());
    }

    private static Resource resource(String pattern, String method) {
        Resource r = new Resource();
        r.setUrlPattern(pattern);
        r.setHttpMethod(method);
        return r;
    }

    // ---- getUserRoles ----

    @Test
    @DisplayName("命中快取:直接回傳,不查 DB")
    void getUserRoles_cacheHit() {
        when(valueOps.get("rbac:user-roles:alice")).thenReturn("[\"MEMBER\",\"LEADER\"]");

        List<String> roles = service.getUserRoles("alice");

        assertThat(roles).containsExactly("MEMBER", "LEADER");
        verifyNoInteractions(roleMapper);
    }

    @Test
    @DisplayName("快取的空清單也算命中(防穿透):不查 DB")
    void getUserRoles_cachedEmptyList() {
        when(valueOps.get("rbac:user-roles:ghost")).thenReturn("[]");

        List<String> roles = service.getUserRoles("ghost");

        assertThat(roles).isEmpty();
        verifyNoInteractions(roleMapper);
    }

    @Test
    @DisplayName("未命中:查 DB 並回寫快取(含 TTL)")
    void getUserRoles_cacheMiss() {
        when(valueOps.get("rbac:user-roles:alice")).thenReturn(null);
        when(roleMapper.selectRoleNamesByUserId("alice")).thenReturn(List.of("MEMBER"));

        List<String> roles = service.getUserRoles("alice");

        assertThat(roles).containsExactly("MEMBER");
        // 回寫:同一個 key、JSON 內容、帶 TTL(任何正時長皆可,不綁死數值)
        verify(valueOps).set(eq("rbac:user-roles:alice"), eq("[\"MEMBER\"]"), any());
    }

    @Test
    @DisplayName("Redis 讀取炸掉:降級直查 DB,不拋例外、不嘗試回寫")
    void getUserRoles_redisDown_fallsBackToDb() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        when(roleMapper.selectRoleNamesByUserId("alice")).thenReturn(List.of("MEMBER"));

        List<String> roles = service.getUserRoles("alice");

        assertThat(roles).containsExactly("MEMBER");
        verify(valueOps, never()).set(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("快取內容損毀(非法 JSON):視同未命中,查 DB 並覆寫")
    void getUserRoles_corruptedCache_treatedAsMiss() {
        when(valueOps.get("rbac:user-roles:alice")).thenReturn("not-json{{{");
        when(roleMapper.selectRoleNamesByUserId("alice")).thenReturn(List.of("MEMBER"));

        List<String> roles = service.getUserRoles("alice");

        assertThat(roles).containsExactly("MEMBER");
        verify(valueOps).set(eq("rbac:user-roles:alice"), eq("[\"MEMBER\"]"), any());
    }

    // ---- getRoleResources ----

    @Test
    @DisplayName("角色資源未命中:查 DB 並回寫快取")
    void getRoleResources_cacheMiss() {
        when(valueOps.get("rbac:role-resources:LEADER")).thenReturn(null);
        when(resourceMapper.selectResourcesByRoleName("LEADER"))
                .thenReturn(List.of(resource("/campaign/**", "DELETE")));

        List<Resource> resources = service.getRoleResources("LEADER");

        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).getUrlPattern()).isEqualTo("/campaign/**");
        verify(valueOps).set(eq("rbac:role-resources:LEADER"), anyString(), any());
    }

    @Test
    @DisplayName("角色資源命中快取:反序列化回傳,不查 DB")
    void getRoleResources_cacheHit() {
        when(valueOps.get("rbac:role-resources:LEADER"))
                .thenReturn("[{\"urlPattern\":\"/campaign/**\",\"httpMethod\":\"DELETE\"}]");

        List<Resource> resources = service.getRoleResources("LEADER");

        assertThat(resources).hasSize(1);
        assertThat(resources.get(0).getHttpMethod()).isEqualTo("DELETE");
        verifyNoInteractions(resourceMapper);
    }

    @Test
    @DisplayName("Redis 炸掉:角色資源降級直查 DB")
    void getRoleResources_redisDown_fallsBackToDb() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        when(resourceMapper.selectResourcesByRoleName("LEADER"))
                .thenReturn(List.of(resource("/campaign/**", "ALL")));

        List<Resource> resources = service.getRoleResources("LEADER");

        assertThat(resources).hasSize(1);
    }

    // ---- getAllResources ----

    @Test
    @DisplayName("全資源清單:miss 時查 DB 並回寫")
    void getAllResources_cacheMiss() {
        when(valueOps.get("rbac:resources:all")).thenReturn(null);
        when(resourceMapper.selectList(null))
                .thenReturn(List.of(resource("/category/create_category", "POST")));

        List<Resource> resources = service.getAllResources();

        assertThat(resources).hasSize(1);
        verify(valueOps).set(eq("rbac:resources:all"), anyString(), any());
    }

    @Test
    @DisplayName("全資源清單:Redis 炸掉降級直查 DB")
    void getAllResources_redisDown_fallsBackToDb() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        when(resourceMapper.selectList(null))
                .thenReturn(List.of(resource("/category/create_category", "POST")));

        assertThat(service.getAllResources()).hasSize(1);
    }

    // ---- evict ----

    @Test
    @DisplayName("失效使用者角色快取:刪對應 key")
    void evictUserRoles_deletesKey() {
        service.evictUserRoles("alice");
        verify(redisTemplate).delete("rbac:user-roles:alice");
    }

    @Test
    @DisplayName("失效角色資源快取:刪對應 key")
    void evictRoleResources_deletesKey() {
        service.evictRoleResources("LEADER");
        verify(redisTemplate).delete("rbac:role-resources:LEADER");
    }

    @Test
    @DisplayName("刪 key 失敗:吞掉例外(TTL 兜底),不影響呼叫方")
    void evict_redisDown_swallowsException() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).delete(anyString());

        assertThatCode(() -> service.evictUserRoles("alice")).doesNotThrowAnyException();
        assertThatCode(() -> service.evictRoleResources("LEADER")).doesNotThrowAnyException();
    }
}
