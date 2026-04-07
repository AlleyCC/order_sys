package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.exception.InsufficientBalanceException;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * CAS debit all users for an order, write transactions, set SETTLED.
     * Runs in a single DB transaction — rolls back all debits on failure.
     */
    @Transactional
    public void executePayment(Order order) {
        String orderId = order.getOrderId();

        // Group items by user
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId)
        );
        Map<String, Long> userTotals = items.stream()
                .collect(Collectors.groupingBy(OrderItem::getUserId, Collectors.summingLong(OrderItem::getSubtotal)));

        // CAS debit each user
        for (Map.Entry<String, Long> entry : userTotals.entrySet()) {
            String userId = entry.getKey();
            long amount = entry.getValue();

            int affected = userMapper.casDebit(userId, amount);
            if (affected == 0) {
                throw new InsufficientBalanceException(userId + " 餘額不足");
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
        }

        order.setStatus(OrderStatus.SETTLED);
        orderMapper.updateById(order);
    }
}
