package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.LoginRequest;
import com.example.orderSystem.dto.request.RefreshRequest;
import com.example.orderSystem.dto.response.LoginResponse;
import com.example.orderSystem.dto.response.RefreshResponse;
import com.example.orderSystem.exception.AuthenticationException;
import com.example.orderSystem.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login/create_token")
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
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login/logout")
    public ResponseEntity<Map<String, String>> logout(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.ok(Map.of("message", "登出成功"));
    }
}
