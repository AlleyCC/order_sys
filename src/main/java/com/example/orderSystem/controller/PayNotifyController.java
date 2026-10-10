package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.PayNotifyRequest;
import com.example.orderSystem.service.RechargeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 付款通道的回呼(免登入)。每個通道一條路徑,不必在 body 裡判斷來源。
 *
 * 回覆 200 = 「收到了,不用再送」:已入帳、尚未付款、查無此單都算。
 * 查通道失敗時例外往外丟、回覆 5xx,通道稍後會重送。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Pay Notify", description = "付款通道回呼")
public class PayNotifyController {

    private final RechargeService rechargeService;

    @PostMapping("/pay/notify/mock")
    @Operation(summary = "模擬通道的付款回呼(只取單號,付款結果以查單為準)")
    public ResponseEntity<String> mockNotify(@Valid @RequestBody PayNotifyRequest request) {
        rechargeService.handleNotify(request.getRechargeId());
        return ResponseEntity.ok("success");
    }
}
