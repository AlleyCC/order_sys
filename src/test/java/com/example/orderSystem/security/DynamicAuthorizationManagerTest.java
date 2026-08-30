package com.example.orderSystem.security;

import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.service.RbacCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 動態授權單元測試(design.md D2 的判斷順序)。
 * RbacCacheService 全 mock——這裡只測「比對與決策」,不測快取。
 *
 * 判斷順序:未認證 deny → SUPER_ADMIN 放行 → 未登記端點放行(登入即可)
 *          → 已登記:任一角色被授權該資源才放行。
 */
@ExtendWith(MockitoExtension.class)
class DynamicAuthorizationManagerTest {

    @Mock RbacCacheService cacheService;

    @InjectMocks DynamicAuthorizationManager manager;

    // ---- helpers ----

    private static Resource resource(String pattern, String method) {
        Resource r = new Resource();
        r.setUrlPattern(pattern);
        r.setHttpMethod(method);
        return r;
    }

    private static RequestAuthorizationContext request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        return new RequestAuthorizationContext(req);
    }

    private static Authentication user(String userId) {
        // 對齊 JwtAuthenticationFilter:principal = userId,無 authorities
        return UsernamePasswordAuthenticationToken.authenticated(
                userId, null, AuthorityUtils.NO_AUTHORITIES);
    }

    private boolean granted(Authentication auth, RequestAuthorizationContext ctx) {
        return manager.check(() -> auth, ctx).isGranted();
    }

    // ---- 未認證 ----

    @Test
    @DisplayName("無 Authentication:deny(交給 401 entry point)")
    void nullAuthentication_denied() {
        assertThat(granted(null, request("GET", "/order/get_all_orders"))).isFalse();
    }

    @Test
    @DisplayName("匿名 Authentication:deny")
    void anonymousAuthentication_denied() {
        Authentication anon = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(granted(anon, request("GET", "/order/get_all_orders"))).isFalse();
    }

    // ---- 超級管理員 ----

    @Test
    @DisplayName("SUPER_ADMIN:一律放行,不需要逐筆查角色資源")
    void superAdmin_alwaysGranted() {
        when(cacheService.getUserRoles("admin")).thenReturn(List.of("SUPER_ADMIN"));

        assertThat(granted(user("admin"), request("POST", "/category/create_category"))).isTrue();
        verify(cacheService, never()).getRoleResources("SUPER_ADMIN");
    }

    // ---- 未登記端點 ----

    @Test
    @DisplayName("未登記端點:登入即可放行(維持既有行為)")
    void unregisteredEndpoint_grantedForAuthenticated() {
        when(cacheService.getUserRoles("alice")).thenReturn(List.of("MEMBER"));
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/category/create_category", "POST")));

        assertThat(granted(user("alice"), request("GET", "/order/get_all_orders"))).isTrue();
    }

    @Test
    @DisplayName("同 URL 但 method 未登記:視為未管制,放行")
    void registeredUrlButDifferentMethod_granted() {
        when(cacheService.getUserRoles("alice")).thenReturn(List.of("MEMBER"));
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/category/create_category", "POST")));

        assertThat(granted(user("alice"), request("GET", "/category/create_category"))).isTrue();
    }

    // ---- 已登記端點 ----

    @Test
    @DisplayName("已登記且角色被授權:放行")
    void registeredAndGranted_allowed() {
        when(cacheService.getUserRoles("leader1")).thenReturn(List.of("LEADER"));
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/campaign/**", "DELETE")));
        when(cacheService.getRoleResources("LEADER"))
                .thenReturn(List.of(resource("/campaign/**", "DELETE")));

        assertThat(granted(user("leader1"), request("DELETE", "/campaign/42"))).isTrue();
    }

    @Test
    @DisplayName("已登記但角色未被授權:deny(403)")
    void registeredButNotGranted_denied() {
        when(cacheService.getUserRoles("alice")).thenReturn(List.of("MEMBER"));
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/category/create_category", "POST")));
        when(cacheService.getRoleResources("MEMBER")).thenReturn(List.of());

        assertThat(granted(user("alice"), request("POST", "/category/create_category"))).isFalse();
    }

    @Test
    @DisplayName("多角色取聯集:任一角色被授權即放行")
    void multiRole_unionGrants() {
        when(cacheService.getUserRoles("hybrid")).thenReturn(List.of("CUSTOMER_SERVICE", "LEADER"));
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/campaign/**", "POST")));
        when(cacheService.getRoleResources("CUSTOMER_SERVICE")).thenReturn(List.of());
        when(cacheService.getRoleResources("LEADER"))
                .thenReturn(List.of(resource("/campaign/**", "POST")));

        assertThat(granted(user("hybrid"), request("POST", "/campaign/create"))).isTrue();
    }

    @Test
    @DisplayName("http_method=ALL:任何動詞都算命中")
    void methodAll_matchesAnyVerb() {
        when(cacheService.getUserRoles("cs1")).thenReturn(List.of("CUSTOMER_SERVICE"));
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/order/**", "ALL")));
        when(cacheService.getRoleResources("CUSTOMER_SERVICE"))
                .thenReturn(List.of(resource("/order/**", "ALL")));

        assertThat(granted(user("cs1"), request("GET", "/order/get_all_orders"))).isTrue();
        assertThat(granted(user("cs1"), request("POST", "/order/create_order"))).isTrue();
    }

    @Test
    @DisplayName("PathPattern 萬用字元:/campaign/** 命中巢狀路徑")
    void wildcardPattern_matchesNestedPath() {
        when(cacheService.getUserRoles("alice")).thenReturn(List.of("MEMBER"));
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/campaign/**", "ALL")));
        when(cacheService.getRoleResources("MEMBER")).thenReturn(List.of());

        // 已登記(deep path 也命中)但未授權 → deny
        assertThat(granted(user("alice"), request("DELETE", "/campaign/delete/42"))).isFalse();
    }

    @Test
    @DisplayName("無任何角色的登入使用者:已登記端點 deny、未登記端點放行")
    void userWithoutRoles() {
        when(cacheService.getUserRoles("norole")).thenReturn(List.of());
        when(cacheService.getAllResources())
                .thenReturn(List.of(resource("/category/create_category", "POST")));

        assertThat(granted(user("norole"), request("POST", "/category/create_category"))).isFalse();
        assertThat(granted(user("norole"), request("GET", "/order/get_all_orders"))).isTrue();
    }
}
