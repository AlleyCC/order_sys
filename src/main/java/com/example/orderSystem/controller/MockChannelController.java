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

/**
 * 扮演「使用者在通道付款」以及「通道送回呼」。只在使用模擬通道時存在。
 *
 * 回呼直接在行程內呼叫處理方法,不發 HTTP:模擬通道和 PayPool 跑在同一個程式裡,
 * 發 HTTP 等於自己打自己,還得先知道自己的 host/port。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.pay.channel", havingValue = "mock", matchIfMissing = true)
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
                // 回呼失敗是「通道沒收到成功回覆」,付款本身已經完成;交給主動查單補上
                log.warn("Mock callback for {} failed", rechargeId, e);
            }
        }
        return ResponseEntity.ok(Map.of("rechargeId", rechargeId, "callbacksDelivered", sent));
    }
}
