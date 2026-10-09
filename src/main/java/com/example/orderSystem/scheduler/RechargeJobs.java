package com.example.orderSystem.scheduler;

import com.example.orderSystem.service.RechargeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 儲值的兩個排程。邏輯都在 RechargeService,這裡只負責觸發與記錄筆數。
 *
 * 用 fixedDelay 而不是 fixedRate:通道慢的時候,上一輪還沒查完不會疊出下一輪。
 * 多台機器會各自跑、重複查同一筆單,重複入帳由條件式寫入擋住,代價只是多餘的查單。
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RechargeJobs {

    private final RechargeService rechargeService;

    @Scheduled(fixedDelay = 30_000)
    public void syncRecentWaiting() {
        int credited = rechargeService.syncRecentWaiting();
        if (credited > 0) {
            log.info("Recharge sync credited {} order(s) whose callback never arrived", credited);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void expireOverdue() {
        int closed = rechargeService.expireOverdue();
        if (closed > 0) {
            log.info("Recharge expiry closed {} unpaid order(s)", closed);
        }
    }
}
