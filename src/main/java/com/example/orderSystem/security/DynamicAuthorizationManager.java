package com.example.orderSystem.security;

import com.example.orderSystem.entity.Resource;
import com.example.orderSystem.service.RbacCacheService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.function.Supplier;

/**
 * 動態授權(design.md D2):每次請求以 DB/Redis 的角色↔資源對照決定放行,
 * 取代寫死的 @PreAuthorize。SS6 的 AuthorizationManager 介面 = 舊三件套
 * (SecurityMetadataSource + AccessDecisionManager + Voter)收斂後的單一決策點。
 *
 * 判斷順序:
 * 1. 未認證 → deny(ExceptionTranslationFilter 對匿名者會轉送 401 entry point)
 * 2. SUPER_ADMIN → 放行(系統不變量,不依賴 role_resources)
 * 3. 請求未命中任何已登記資源 → 放行(登入即可,維持既有行為)
 * 4. 已登記 → 任一角色的授權資源命中才放行,否則 deny(403)
 */
@Component
@RequiredArgsConstructor
public class DynamicAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    static final String SUPER_ADMIN = "SUPER_ADMIN";
    static final String METHOD_ALL = "ALL";

    private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);
    private static final AuthorizationDecision DENIED = new AuthorizationDecision(false);

    private final RbacCacheService cacheService;
    private final PathPatternParser patternParser = PathPatternParser.defaultInstance;

    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication,
                                       RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return DENIED;
        }

        List<String> roles = cacheService.getUserRoles(auth.getName());
        if (roles.contains(SUPER_ADMIN)) {
            return GRANTED;
        }

        HttpServletRequest request = context.getRequest();
        String method = request.getMethod();
        PathContainer path = PathContainer.parsePath(request.getRequestURI());

        boolean managed = cacheService.getAllResources().stream()
                .anyMatch(r -> matches(r, method, path));
        if (!managed) {
            return GRANTED;
        }

        boolean allowed = roles.stream()
                .flatMap(role -> cacheService.getRoleResources(role).stream())
                .anyMatch(r -> matches(r, method, path));
        return allowed ? GRANTED : DENIED;
    }

    private boolean matches(Resource resource, String method, PathContainer path) {
        boolean methodOk = METHOD_ALL.equalsIgnoreCase(resource.getHttpMethod())
                || method.equalsIgnoreCase(resource.getHttpMethod());
        return methodOk && patternParser.parse(resource.getUrlPattern()).matches(path);
    }
}
