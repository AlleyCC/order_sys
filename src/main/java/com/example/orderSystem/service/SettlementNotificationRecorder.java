package com.example.orderSystem.service;

import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.NotificationType;
import com.example.orderSystem.mapper.NotificationMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 結算結果的站內通知:組內容、寫入 notifications。
 *
 * 本身不開交易,由呼叫端決定交易邊界 —— 成功通知要跟扣款同一個交易、
 * 失敗通知要跟「訂單轉為 FAILED」同一個交易,才會一起成立或一起回滾。
 *
 * 團主同時是參與者時,只寫團主版一則(團主版已包含本人的資訊)。
 */
@Component
@RequiredArgsConstructor
public class SettlementNotificationRecorder {

    private static final int CONTENT_MAX = 255;

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;

    /**
     * @param amounts  參與者 → 本次被扣金額
     * @param balances 參與者 → 扣款後餘額
     */
    public List<Notification> recordSucceeded(Order order, Map<String, Long> amounts, Map<String, Long> balances) {
        String owner = order.getCreatedBy();
        String name = order.getOrderName();
        List<Notification> written = new ArrayList<>();

        for (Map.Entry<String, Long> entry : amounts.entrySet()) {
            String userId = entry.getKey();
            if (userId.equals(owner)) {
                continue;
            }
            written.add(insert(userId, order, NotificationType.SETTLEMENT_SUCCEEDED,
                    String.format("「%s」已結算,扣款 %d 元,餘額 %d 元", name, entry.getValue(), balances.get(userId))));
        }

        long total = amounts.values().stream().mapToLong(Long::longValue).sum();
        String ownerContent = String.format("「%s」結算成功,共收 %d 元,可以向店家訂購", name, total);
        if (amounts.containsKey(owner)) {
            ownerContent += String.format("。你被扣款 %d 元,餘額 %d 元", amounts.get(owner), balances.get(owner));
        }
        written.add(insert(owner, order, NotificationType.SETTLEMENT_SUCCEEDED_OWNER, ownerContent));
        return written;
    }

    /**
     * @param amounts      參與者 → 本次應付金額
     * @param shortUserIds 餘額不足的參與者
     */
    public List<Notification> recordFailed(Order order, Map<String, Long> amounts, List<String> shortUserIds) {
        String owner = order.getCreatedBy();
        String name = order.getOrderName();
        List<Notification> written = new ArrayList<>();

        for (Map.Entry<String, Long> entry : amounts.entrySet()) {
            String userId = entry.getKey();
            if (userId.equals(owner)) {
                continue;
            }
            if (shortUserIds.contains(userId)) {
                written.add(insert(userId, order, NotificationType.SETTLEMENT_INSUFFICIENT,
                        String.format("「%s」結算失敗:你的餘額不足 %d 元,請儲值後聯繫團主重新結算",
                                name, entry.getValue())));
            } else {
                written.add(insert(userId, order, NotificationType.SETTLEMENT_BLOCKED,
                        String.format("「%s」暫未成立:有參與者餘額不足,你的 %d 元仍凍結中,待團主重新結算",
                                name, entry.getValue())));
            }
        }

        written.add(insert(owner, order, NotificationType.SETTLEMENT_FAILED_OWNER,
                String.format("「%s」結算失敗,餘額不足:%s。請通知他們儲值後重新結算",
                        name, displayNames(shortUserIds))));
        return written;
    }

    /**
     * 同一張訂單、同一位收件人只會有一則;重複寫入時由唯一鍵擋下,
     * 拋出 {@link org.springframework.dao.DuplicateKeyException} 交給呼叫端略過。
     */
    public Notification recordAbandoned(Order order, String userId) {
        Notification n = build(userId, order, NotificationType.SETTLEMENT_ABANDONED,
                String.format("「%s」系統結算失敗,已停止自動重試,請手動結算", order.getOrderName()));
        n.setDedupKey("ABANDONED:" + order.getOrderId());
        notificationMapper.insert(n);
        return n;
    }

    private Notification insert(String userId, Order order, NotificationType type, String content) {
        Notification n = build(userId, order, type, content);
        notificationMapper.insert(n);
        return n;
    }

    private Notification build(String userId, Order order, NotificationType type, String content) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID().toString());
        n.setUserId(userId);
        n.setOrderId(order.getOrderId());
        n.setType(type);
        n.setContent(content.length() > CONTENT_MAX ? content.substring(0, CONTENT_MAX - 1) + "…" : content);
        return n;
    }

    private String displayNames(List<String> userIds) {
        Map<String, String> names = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getUserId, User::getUserName, (a, b) -> a));
        return userIds.stream()
                .map(id -> names.getOrDefault(id, id))
                .collect(Collectors.joining("、"));
    }
}
