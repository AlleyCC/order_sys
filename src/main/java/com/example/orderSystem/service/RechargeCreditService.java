package com.example.orderSystem.service;

import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.event.BalanceChangedEvent;
import com.example.orderSystem.mapper.RechargeOrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/**
 * 儲值入帳的唯一入口。回呼、主動查單、過期關單、人工對單只負責問出「付了沒」,
 * 確定付了才呼叫這裡。
 *
 * 獨立成一個 bean,是因為 @Transactional 靠 proxy 生效:
 * 若和查單寫在同一個 class 裡互相呼叫,會繞過 proxy,交易就不存在。
 */
@Service
@RequiredArgsConstructor
public class RechargeCreditService {

    private final RechargeOrderMapper rechargeOrderMapper;
    private final UserMapper userMapper;
    private final TransactionMapper transactionMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 先搶下儲值單狀態再加餘額:同一筆單同時被入帳時,只有 UPDATE 成功的那個能往下走;
     * 後面任一步失敗,狀態會跟著一起回滾。
     *
     * @param allowedFrom 自動路徑只傳 WAITING;人工對單才會傳 WAITING + CLOSED
     * @return false 代表這次沒有入帳(已入帳過,或狀態不允許)
     */
    @Transactional
    public boolean credit(String rechargeId, Collection<RechargeStatus> allowedFrom, String operator) {
        int claimed = rechargeOrderMapper.markPaidIfIn(rechargeId, LocalDateTime.now(clock), allowedFrom);
        if (claimed == 0) {
            return false;
        }

        RechargeOrder order = rechargeOrderMapper.selectById(rechargeId);
        userMapper.creditBalance(order.getUserId(), order.getAmount());

        // 上一行的 UPDATE 已持有 users 的列鎖,這裡讀到的就是本次入帳後的餘額
        User user = userMapper.selectById(order.getUserId());
        Transaction txn = new Transaction();
        txn.setTransactionId(UUID.randomUUID().toString());
        txn.setUserId(order.getUserId());
        txn.setRechargeId(rechargeId);
        txn.setAmount(order.getAmount());
        txn.setClosingBalance(user.getBalance().intValue());
        txn.setType(TradeType.RECHARGE);
        txn.setCreatedBy(operator);
        transactionMapper.insert(txn);

        eventPublisher.publishEvent(new BalanceChangedEvent(order.getUserId(), "儲值入帳：" + order.getAmount()));
        return true;
    }
}
