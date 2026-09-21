package com.example.orderSystem.service;

import com.example.orderSystem.entity.Role;
import com.example.orderSystem.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RbacCacheInitializer {

    private final RbacCacheService rbacCacheService;
    private final RoleMapper roleMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void evictMigrationManagedCaches() {
        rbacCacheService.evictAllResources();

        List<Role> roles = roleMapper.selectList(null);
        for (Role role : roles) {
            rbacCacheService.evictRoleResources(role.getName());
        }

        log.info("Evicted RBAC resource caches on startup: resources:all + {} roles", roles.size());
    }
}
