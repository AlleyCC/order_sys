package com.example.orderSystem.controller;

import com.example.orderSystem.payment.MockPayChannel;
import com.example.orderSystem.service.RechargeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.pay.channel", havingValue = "mock")
@Tag(name = "Mock Channel", description = "模擬付款通道(開發用)")
public class MockChannelController {

    public enum Callback {
        ONCE(1), TWICE(2), DROP(0);

        private final int times;

        Callback(int times) {
            this.times = times;
        }
    }

    private final MockPayChannel mockPayChannel;
    private final RechargeService rechargeService;

    @PostMapping("/mock-channel/pay")
    @Operation(summary = "模擬付款;callback 決定回呼送一次、兩次或不送")
    public ResponseEntity<Map<String, Object>> pay(@RequestParam String rechargeId,
                                                   @RequestParam Callback callback) {
        mockPayChannel.markPaid(rechargeId);

        int sent = 0;
        for (int i = 0; i < callback.times; i++) {
            try {
                rechargeService.handleNotify(rechargeId);
                sent++;
            } catch (Exception e) {
                log.warn("Mock callback for {} failed", rechargeId, e);
            }
        }
        return ResponseEntity.ok(Map.of("rechargeId", rechargeId, "callbacksDelivered", sent));
    }
}
