package com.example.orderSystem.service;

import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.mapper.ResourceMapper;
import com.example.orderSystem.mapper.RoleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * RBAC 權限資料的 Cache-Aside 快取(design.md D3/D4)。
 * 讀:Redis 命中直接回;miss 查 DB 回寫。Redis 故障 → 降級直查 DB,不中斷授權。
 * 寫(管理端調整後):只刪 key 不更新,下次讀取時重建;TTL 兜底防漏刪。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RbacCacheService {

    static final String USER_ROLES_PREFIX = "rbac:user-roles:";
    static final String ROLE_RESOURCES_PREFIX = "rbac:role-resources:";
    static final String ALL_RESOURCES_KEY = "rbac:resources:all";

    /** TTL 只是漏刪時的兜底,即時性靠管理端寫入後主動刪 key */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final RoleMapper roleMapper;
    private final ResourceMapper resourceMapper;
    private final ObjectMapper objectMapper;

    public List<String> getUserRoles(String userId) {
        return readThrough(
                USER_ROLES_PREFIX + userId,
                new TypeReference<List<String>>() {},
                () -> roleMapper.selectRoleNamesByUserId(userId));
    }

    public List<Resource> getRoleResources(String roleName) {
        return readThrough(
                ROLE_RESOURCES_PREFIX + roleName,
                new TypeReference<List<Resource>>() {},
                () -> resourceMapper.selectResourcesByRoleName(roleName));
    }

    /**
     * 全部已登記資源:授權層判斷「這個端點是否受管制」用。
     * 本次 scope 內 resources 表只由 migration 變動,失效靠 TTL;
     * evictAllResources() 留給未來的資源 CRUD。
     */
    public List<Resource> getAllResources() {
        return readThrough(
                ALL_RESOURCES_KEY,
                new TypeReference<List<Resource>>() {},
                () -> resourceMapper.selectList(null));
    }

    public void evictUserRoles(String userId) {
        evict(USER_ROLES_PREFIX + userId);
    }

    public void evictAllResources() {
        evict(ALL_RESOURCES_KEY);
    }

    public void evictRoleResources(String roleName) {
        evict(ROLE_RESOURCES_PREFIX + roleName);
    }

    /**
     * Cache-Aside 讀取。三種路徑:
     * 1. Redis 正常且命中(含空清單,防穿透)→ 直接回
     * 2. Redis 正常但 miss / 內容損毀 → 查 DB,回寫後回傳
     * 3. Redis 讀取拋例外 → 降級直查 DB,跳過回寫(Redis 已知故障,再寫只是多等一次 timeout)
     */
    private <T> List<T> readThrough(String key, TypeReference<List<T>> type, Supplier<List<T>> dbQuery) {
        boolean redisAvailable = true;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                try {
                    return objectMapper.readValue(cached, type);
                } catch (Exception parseError) {
                    // 內容損毀:視同 miss,往下查 DB 並覆寫
                    log.warn("RBAC 快取內容損毀,視同未命中 key={}", key, parseError);
                }
            }
        } catch (Exception redisError) {
            redisAvailable = false;
            log.warn("RBAC 快取讀取失敗,降級直查 DB key={}", key, redisError);
        }

        List<T> fromDb = dbQuery.get();

        if (redisAvailable) {
            try {
                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(fromDb), CACHE_TTL);
            } catch (Exception writeError) {
                log.warn("RBAC 快取回寫失敗(不影響本次授權)key={}", key, writeError);
            }
        }
        return fromDb;
    }

    private void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            // 刪失敗只 log:TTL 到期會自然失效,授權最壞短暫用到舊權限
            log.warn("RBAC 快取刪除失敗,依賴 TTL 兜底 key={}", key, e);
        }
    }
}
