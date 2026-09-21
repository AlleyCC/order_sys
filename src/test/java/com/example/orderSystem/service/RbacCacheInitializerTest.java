package com.example.orderSystem.service;

import com.example.orderSystem.entity.Role;
import com.example.orderSystem.mapper.RoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RbacCacheInitializerTest {

    @InjectMocks
    private RbacCacheInitializer initializer;

    @Mock
    private RbacCacheService rbacCacheService;
    @Mock
    private RoleMapper roleMapper;

    @Test
    @DisplayName("啟動時清掉 resources:all 與每個角色的資源快取")
    void evictsResourceCachesOnStartup() {
        when(roleMapper.selectList(any())).thenReturn(List.of(
                role("SUPER_ADMIN"), role("ADMIN_STAFF"), role("CUSTOMER_SERVICE"), role("ACCOUNTANT")));

        initializer.evictMigrationManagedCaches();

        verify(rbacCacheService).evictAllResources();
        verify(rbacCacheService).evictRoleResources("SUPER_ADMIN");
        verify(rbacCacheService).evictRoleResources("ADMIN_STAFF");
        verify(rbacCacheService).evictRoleResources("CUSTOMER_SERVICE");
        verify(rbacCacheService).evictRoleResources("ACCOUNTANT");
    }

    @Test
    @DisplayName("不碰 per-user 的角色快取(數量不設限,留給 TTL)")
    void doesNotEvictUserRoleCaches() {
        when(roleMapper.selectList(any())).thenReturn(List.of(role("SUPER_ADMIN")));

        initializer.evictMigrationManagedCaches();

        verify(rbacCacheService, never()).evictUserRoles(any());
    }

    private Role role(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }
}
