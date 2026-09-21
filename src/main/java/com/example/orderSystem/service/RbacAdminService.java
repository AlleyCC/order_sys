package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.entity.Role;
import com.example.orderSystem.entity.RoleResource;
import com.example.orderSystem.entity.UserRole;
import com.example.orderSystem.exception.BadRequestException;
import com.example.orderSystem.exception.ResourceNotFoundException;
import com.example.orderSystem.mapper.ResourceMapper;
import com.example.orderSystem.mapper.RoleMapper;
import com.example.orderSystem.mapper.RoleResourceMapper;
import com.example.orderSystem.mapper.UserRoleMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * RBAC 管理端(spec: rbac-management)。
 * 寫入模式一律「全量替換」:先刪後插,冪等且不用分辨新增/移除。
 * 每次寫 DB 成功後刪對應 Redis key(Cache-Aside),使用者下一個請求即套用新權限。
 *
 * 授權(僅超管)不在這裡處理:/admin/rbac/** 已登記於 resources 表且不掛角色,
 * 動態授權層在進 controller 前就把非超管擋掉了。
 */
@Service
@RequiredArgsConstructor
public class RbacAdminService {

    private final RoleMapper roleMapper;
    private final ResourceMapper resourceMapper;
    private final RoleResourceMapper roleResourceMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final RbacCacheService rbacCacheService;

    private void evictAfterCommit(Runnable eviction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eviction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eviction.run();
            }
        });
    }

    public List<Role> listRoles() {
        return roleMapper.selectList(null);
    }

    public List<Resource> listResources() {
        return resourceMapper.selectList(null);
    }

    public List<Resource> getRoleResources(String roleName) {
        requireRole(roleName);
        return resourceMapper.selectResourcesByRoleName(roleName);
    }

    @Transactional
    public void updateRoleResources(String roleName, List<Long> resourceIds) {
        Role role = requireRole(roleName);

        // 整批驗證 resourceIds 都存在,有任何一個不存在就整批拒絕(400),避免部分寫入
        if (!resourceIds.isEmpty()
                && resourceMapper.selectBatchIds(resourceIds).size() != resourceIds.stream().distinct().count()) {
            throw new BadRequestException("resourceIds 含不存在的資源");
        }

        roleResourceMapper.delete(new LambdaQueryWrapper<RoleResource>()
                .eq(RoleResource::getRoleId, role.getRoleId()));
        for (Long resourceId : resourceIds.stream().distinct().toList()) {
            RoleResource rr = new RoleResource();
            rr.setRoleId(role.getRoleId());
            rr.setResourceId(resourceId);
            roleResourceMapper.insert(rr);
        }

        // DB 寫完才失效快取;刪 key 失敗由 RbacCacheService 吞掉(TTL 兜底)
        evictAfterCommit(() -> rbacCacheService.evictRoleResources(roleName));
    }

    @Transactional
    public void updateUserRoles(String userId, List<String> roleNames) {
        if (userMapper.selectById(userId) == null) {
            throw new ResourceNotFoundException("使用者不存在: " + userId);
        }

        List<String> distinctNames = roleNames.stream().distinct().toList();
        List<Role> roles = distinctNames.isEmpty() ? List.of()
                : roleMapper.selectList(new LambdaQueryWrapper<Role>().in(Role::getName, distinctNames));
        if (roles.size() != distinctNames.size()) {
            throw new BadRequestException("roleNames 含不存在的角色");
        }

        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUserId, userId));
        for (Role role : roles) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(role.getRoleId());
            userRoleMapper.insert(ur);
        }

        evictAfterCommit(() -> rbacCacheService.evictUserRoles(userId));
    }

    private Role requireRole(String roleName) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>().eq(Role::getName, roleName));
        if (role == null) {
            throw new ResourceNotFoundException("角色不存在: " + roleName);
        }
        return role;
    }
}
