package com.example.orderSystem.service;

import com.example.orderSystem.event.BalanceChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceChangeNotifier {

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBalanceChanged(BalanceChangedEvent event) {
        try {
            notificationService.sendBalanceChanged(event.userId(), event.reason());
        } catch (Exception e) {
            log.error("Failed to send balance update for user={}", event.userId(), e);
        }
    }
}
