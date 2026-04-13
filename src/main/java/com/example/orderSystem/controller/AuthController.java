package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.LoginRequest;
import com.example.orderSystem.dto.request.RefreshRequest;
import com.example.orderSystem.dto.response.LoginResponse;
import com.example.orderSystem.dto.response.RefreshResponse;
import com.example.orderSystem.exception.AuthenticationException;
import com.example.orderSystem.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Auth", description = "登入 / 登出 / Refresh Token")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login/create_token")
    @SecurityRequirements
    @Operation(summary = "登入並取得 access / refresh token",
            description = "Header 需帶 `Authorization: JWT <RSA 加密後的密碼>`；成功回傳 access + refresh token。")
    public ResponseEntity<LoginResponse> login(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody LoginRequest request) {

        // Header format: "JWT <RSA encrypted password>"
        if (!authorization.startsWith("JWT ")) {
            throw new AuthenticationException("帳密錯誤");
        }
        String encryptedPassword = authorization.substring(4);

        LoginResponse response = authService.login(request, encryptedPassword);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/auth/refresh")
    @SecurityRequirements
    @Operation(summary = "以 refresh token 換取新的 access token")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login/logout")
    @Operation(summary = "登出並撤銷 refresh token")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        String accessToken = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            accessToken = authorization.substring(7);
        }
        authService.logout(refreshToken, accessToken);
        return ResponseEntity.ok(Map.of("message", "登出成功"));
    }
}
