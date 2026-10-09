package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.orderSystem.dto.response.TransactionResponse;
import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.exception.ResourceNotFoundException;
import com.example.orderSystem.mapper.NotificationMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final TransactionMapper transactionMapper;
    private final UserMapper userMapper;
    private final NotificationMapper notificationMapper;

    public List<TransactionResponse> getTransactionRecord(String userId) {
        if (userMapper.selectById(userId) == null) {
            throw new ResourceNotFoundException("使用者不存在: " + userId);
        }

        List<Transaction> transactions = transactionMapper.selectList(
                new LambdaQueryWrapper<Transaction>()
                        .eq(Transaction::getUserId, userId)
                        .orderByDesc(Transaction::getCreatedAt)
        );

        return transactions.stream().map(t -> {
            TransactionResponse resp = new TransactionResponse();
            resp.setTransactionId(t.getTransactionId());
            // RECHARGE = positive, DEBIT = negative
            resp.setAmount(t.getType() == TradeType.DEBIT ? -t.getAmount() : t.getAmount());
            resp.setCreatedAt(t.getCreatedAt());
            return resp;
        }).toList();
    }

    public IPage<Notification> getUnreadNotifications(String userId, int page, int size) {
        return notificationMapper.selectUnread(new Page<>(page, size), userId);
    }

    /**
     * 標為已讀,重複標記視為成功。
     * 不存在與「不是自己的」都回 404,不讓呼叫者藉此探測別人的通知 id 是否存在。
     */
    public void markNotificationRead(String userId, String notificationId) {
        if (notificationMapper.markRead(notificationId, userId) == 1) {
            return;
        }
        boolean ownedByCaller = notificationMapper.exists(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getNotificationId, notificationId)
                .eq(Notification::getUserId, userId));
        if (!ownedByCaller) {
            throw new ResourceNotFoundException("通知不存在");
        }
    }
}
