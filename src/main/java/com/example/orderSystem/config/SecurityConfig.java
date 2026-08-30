package com.example.orderSystem.config;

import com.example.orderSystem.security.DynamicAuthorizationManager;
import com.example.orderSystem.security.JwtAuthenticationFilter;
import com.example.orderSystem.security.SecurityConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final DynamicAuthorizationManager dynamicAuthorizationManager;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(SecurityConstants.WHITE_LIST).permitAll()
                // 其餘全部交給動態授權:規則在 DB(roles/resources/role_resources),
                // 管理端調整後即時生效,取代寫死的 @PreAuthorize
                .anyRequest().access(dynamicAuthorizationManager)
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(ex -> ex
                // 401:根本沒登入(或憑證失效)。匿名者被 deny 時,
                // ExceptionTranslationFilter 會轉送到這裡而不是 accessDeniedHandler。
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"status\":401,\"detail\":\"未登入或憑證已失效\"}");
                })
                // 403:已登入但權限不足。與 401 分開,前端才能分別處理。
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"status\":403,\"detail\":\"權限不足\"}");
                })
            );

        return http.build();
    }
}
