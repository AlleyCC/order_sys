package com.example.orderSystem.service;

import com.example.orderSystem.entity.User;
import com.example.orderSystem.event.BalanceChangedEvent;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 餘額變動通知,在交易 commit 之後才送出。
 *
 * 放在交易外有兩個理由:
 * 1. 交易若回滾,使用者不該已經收到「下單成功」的推播。
 * 2. createUserOrder 在交易期間持有 users 的行鎖,推播是外部 I/O,
 *    不該把它算進持鎖時間裡。
 *
 * 直接依賴 mapper 而非 OrderService,避免 OrderService → event → listener → OrderService
 * 的循環依賴。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BalanceChangeNotifier {

    private final UserMapper userMapper;
    private final OrderItemMapper orderItemMapper;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBalanceChanged(BalanceChangedEvent event) {
        try {
            User user = userMapper.selectById(event.userId());
            if (user == null) {
                return;
            }
            long available = user.getBalance() - orderItemMapper.getFrozenAmount(event.userId());
            notificationService.sendBalanceUpdate(event.userId(), available, event.reason());
        } catch (Exception e) {
            // 交易已經 commit,下單本身是成立的;推播失敗只記錄,不能反過來影響結果
            log.error("Failed to send balance update for user={}", event.userId(), e);
        }
    }
}
