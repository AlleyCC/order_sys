package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.exception.ConflictException;
import com.example.orderSystem.exception.InsufficientBalanceException;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserMapper userMapper;
    private final TransactionMapper transactionMapper;
    private final SettlementNotificationRecorder notificationRecorder;

    /**
     * Claim the order (CLOSED/FAILED → SETTLED), then CAS debit all users and write transactions.
     * Runs in a single DB transaction — rolls back the claim and all debits on failure.
     * 結算成功通知也寫在同一個交易,扣款成立通知就一定存在。
     *
     * @return 本次寫入的通知,交由呼叫端在 commit 之後推播
     */
    @Transactional
    public List<Notification> executePayment(Order order) {
        String orderId = order.getOrderId();

        // 先搶下訂單狀態再扣款:同一張單同時被結算時(自動重試 vs 手動 pay_order),
        // 只有 UPDATE 成功的那個能往下扣;失敗 rollback 時狀態也會一起退回。
        int claimed = orderMapper.updateStatusIfIn(orderId, OrderStatus.SETTLED,
                List.of(OrderStatus.CLOSED, OrderStatus.FAILED));
        if (claimed == 0) {
            throw new ConflictException("訂單狀態已變更，無法結算");
        }

        // Group items by user
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId)
        );
        Map<String, Long> userTotals = sumByUser(items);

        // CAS debit each user。遇到餘額不足不立刻停,試扣完所有人才知道「誰」不夠;
        // 有人不足就整個交易回滾,已試扣成功的人在 commit 前外界看不到,不會真的被扣款。
        List<String> shortUserIds = new ArrayList<>();
        Map<String, Long> balances = new HashMap<>();
        for (Map.Entry<String, Long> entry : userTotals.entrySet()) {
            String userId = entry.getKey();
            long amount = entry.getValue();

            int affected = userMapper.casDebit(userId, amount);
            if (affected == 0) {
                shortUserIds.add(userId);
                continue;
            }

            // Read new balance and insert transaction
            User user = userMapper.selectById(userId);
            Transaction txn = new Transaction();
            txn.setTransactionId(UUID.randomUUID().toString());
            txn.setUserId(userId);
            txn.setOrderId(orderId);
            txn.setAmount(Math.toIntExact(amount));
            txn.setClosingBalance(user.getBalance().intValue());
            txn.setType(TradeType.DEBIT);
            txn.setCreatedBy(order.getCreatedBy());
            transactionMapper.insert(txn);
            balances.put(userId, user.getBalance());
        }

        if (!shortUserIds.isEmpty()) {
            throw new InsufficientBalanceException(shortUserIds);
        }

        order.setStatus(OrderStatus.SETTLED);
        return notificationRecorder.recordSucceeded(order, userTotals, balances);
    }

    /**
     * 餘額不足後把訂單轉為 FAILED,並在同一個交易寫入失敗通知。
     * 條件式寫入:讀到餘額不足之後,訂單可能已被另一次結算改成 SETTLED,
     * 這時不能蓋回 FAILED,也不能發「結算失敗」。FAILED → FAILED(團主重試又失敗)算一次新的失敗。
     *
     * @return 本次寫入的通知;訂單狀態已被改掉時為空
     */
    @Transactional
    public List<Notification> markFailed(Order order, List<String> shortUserIds) {
        String orderId = order.getOrderId();
        int updated = orderMapper.updateStatusIfIn(orderId, OrderStatus.FAILED,
                List.of(OrderStatus.CLOSED, OrderStatus.FAILED));
        if (updated == 0) {
            return List.of();
        }
        order.setStatus(OrderStatus.FAILED);

        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId)
        );
        return notificationRecorder.recordFailed(order, sumByUser(items), shortUserIds);
    }

    private static Map<String, Long> sumByUser(List<OrderItem> items) {
        return items.stream()
                .collect(Collectors.groupingBy(OrderItem::getUserId, Collectors.summingLong(OrderItem::getSubtotal)));
    }
}
