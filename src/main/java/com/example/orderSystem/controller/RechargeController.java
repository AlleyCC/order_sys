package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.CreateRechargeRequest;
import com.example.orderSystem.dto.response.RechargeOrderResponse;
import com.example.orderSystem.service.RechargeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Recharge", description = "使用者儲值")
public class RechargeController {

    private final RechargeService rechargeService;

    @PostMapping("/user/create_recharge_order")
    @Operation(summary = "建立儲值單,回傳付款資訊(儲值對象一律是登入者本人)")
    public ResponseEntity<RechargeOrderResponse> createRechargeOrder(
            @Valid @RequestBody CreateRechargeRequest request,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(rechargeService.createRechargeOrder(userId, request.getAmount().intValueExact()));
    }

    @GetMapping("/user/get_recharge_order")
    @Operation(summary = "查詢自己的儲值單")
    public ResponseEntity<RechargeOrderResponse> getRechargeOrder(
            @RequestParam String rechargeId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(rechargeService.getOwnRechargeOrder(userId, rechargeId));
    }
}
