package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.CreateOrderRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
import com.example.orderSystem.dto.response.CreateOrderItemResult;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.*;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.event.BalanceChangedEvent;
import com.example.orderSystem.exception.*;
import com.example.orderSystem.mapper.*;
import com.example.orderSystem.scheduler.RedisSettlementQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<String> RESCUE_ROLES = Set.of("SUPER_ADMIN", "CUSTOMER_SERVICE");

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final StoreMapper storeMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final MenuMapper menuMapper;
    private final UserMapper userMapper;
    private final RedisSettlementQueue settlementQueue;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;
    private final RbacCacheService rbacCacheService;
    private final RoleMapper roleMapper;
    private final SettlementNotificationRecorder notificationRecorder;
    private final OrderItemIdempotencyKeyMapper idempotencyKeyMapper;

    public IPage<Store> getAllShops(String storeName, int page, int size) {
        String name = StringUtils.hasText(storeName) ? escapeLike(storeName.trim()) : null;

        LambdaQueryWrapper<Store> query = new LambdaQueryWrapper<Store>()
                .like(name != null, Store::getStoreName, name)
                .orderByAsc(Store::getMinOrderAmount)
                .orderByAsc(Store::getStoreId);

        return storeMapper.selectPage(new Page<>(page, size), query);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public IPage<Map<String, Object>> getOpenOrders(int page, int size) {
        return orderMapper.getOpenOrdersWithStore(new Page<>(page, size));
    }

    public OrderDetailResponse getOrderDetail(String orderId, String userId) {
        OrderDetailResponse detail = orderMapper.getOrderDetail(orderId);
        if (detail == null || detail.getOrderId() == null || !isParticipant(detail, userId)) {
            throw new ForbiddenException("無權限查看此訂單");
        }
        return detail;
    }

    public IPage<Map<String, Object>> getAllOrdersUnscoped(int page, int size) {
        return orderMapper.getAllOrdersWithStore(new Page<>(page, size));
    }

    public OrderDetailResponse getOrderDetailUnscoped(String orderId) {
        return loadOrderDetail(orderId);
    }

    private OrderDetailResponse loadOrderDetail(String orderId) {
        OrderDetailResponse detail = orderMapper.getOrderDetail(orderId);
        if (detail == null || detail.getOrderId() == null) {
            throw new ResourceNotFoundException("訂單不存在");
        }
        return detail;
    }

    private boolean canRescue(String userId) {
        return rbacCacheService.getUserRoles(userId).stream().anyMatch(RESCUE_ROLES::contains);
    }

    private boolean isParticipant(OrderDetailResponse detail, String userId) {
        if (userId.equals(detail.getCreatedBy())) {
            return true;
        }
        return detail.getOrderItems() != null && detail.getOrderItems().stream()
                .anyMatch(item -> userId.equals(item.getUserId()));
    }

    public Map<String, Object> getUserAccount(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("使用者不存在");
        }

        long balance = user.getBalance();
        long frozenAmount = orderItemMapper.getFrozenAmount(userId);

        return Map.of(
                "balance", balance,
                "availableBalance", balance - frozenAmount
        );
    }

    // ========== Write APIs ==========

    public Map<String, String> createOrder(CreateOrderRequest request, String userId) {
        Store store = storeMapper.selectById(request.getStoreId());
        if (store == null) {
            throw new ResourceNotFoundException("店家不存在");
        }

        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setStoreId(request.getStoreId());
        order.setCreatedBy(userId);
        order.setOrderName(request.getOrderName());
        order.setStatus(OrderStatus.OPEN);
        order.setDeadline(LocalDateTime.parse(request.getDeadline(), DATETIME_FMT));
        orderMapper.insert(order);

        // Schedule auto-settlement at deadline
        settlementQueue.add(order.getOrderId(), order.getDeadline());

        return Map.of("orderId", order.getOrderId(), "storeId", order.getStoreId());
    }

    /**
     * 隔離等級刻意降到 READ_COMMITTED。
     * MySQL 預設是 REPEATABLE READ:交易內第一個「一般 SELECT」就決定了快照,
     * 之後所有一般 SELECT 都讀那個快照。只要鎖之前有任何一般 SELECT,
     * 快照就早於對手的 commit —— 就算 selectForUpdate 拿到了鎖(它是當前讀、看得到最新),
     * 後面 getFrozenAmount 這個一般 SELECT 仍會讀到舊快照、看不見對手剛寫入的品項,
     * 鎖等於白加。READ_COMMITTED 下每個 SELECT 都讀最新已提交,不必依賴「鎖一定是第一句」。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateOrderItemResult createUserOrder(CreateOrderItemRequest request, String userId,
                                                 String idempotencyKey) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("Idempotency-Key 格式錯誤:限 1 到 64 個英數字、- 或 _");
        }

        // 1. 第一步就鎖 users 這一行,同一使用者的所有下單在這裡排隊。
        //    凍結是 order_items 上的 SUM、沒有實體可鎖,所以改鎖 users:
        //    鎖的對象不必是要保護的資料,只要是所有寫入者都必經的同一個點。
        //    這把鎖同時讓下一步 Idempotency-Key 的「先查再寫」不會有競爭。
        User user = userMapper.selectForUpdate(userId);
        if (user == null) {
            throw new ResourceNotFoundException("使用者不存在");
        }

        // 2. Idempotency-Key:同一使用者的請求都在上面的 users 鎖排隊,
        //    所以這裡「先查、沒有才在最後寫入」不會有兩個請求同時查到「沒有」。
        //    - 查到 → 回放第一次的結果,不再驗證、不寫入、不推播
        //    - 查不到 → 照常下單,寫入品項後才寫入 key;下單失敗時交易回滾,不留 key
        //    寫入 key 的路徑都必須先鎖 users,否則這個「先查」就是 check-then-act。
        //    (唯一索引仍在,作為最後一道防線。)
        if (idempotencyKey != null) {
            OrderItemIdempotencyKey existing = idempotencyKeyMapper.selectOne(
                    new LambdaQueryWrapper<OrderItemIdempotencyKey>()
                            .eq(OrderItemIdempotencyKey::getUserId, userId)
                            .eq(OrderItemIdempotencyKey::getIdempotencyKey, idempotencyKey));
            if (existing != null) {
                return new CreateOrderItemResult(existing.getItemId(), true);
            }
        }

        // 3. Validate order
        Order order = orderMapper.selectById(request.getOrderId());
        if (order == null) {
            throw new ResourceNotFoundException("該筆訂單不存在");
        }
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new IllegalStateException("訂單非開團中狀態");
        }
        if (order.getDeadline().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("訂單已過截止時間");
        }

        // 4. Validate menu
        Menu menu = menuMapper.selectById(request.getMenuId());
        if (menu == null) {
            throw new ResourceNotFoundException("菜單品項不存在");
        }
        if (!menu.getIsAvailable()) {
            throw new IllegalStateException("該品項已下架");
        }

        // 5. Check available balance —— 必須在鎖住之後才讀,否則是 check-then-act:
        //    兩個併發請求會讀到同一份可用餘額而雙雙放行,凍結因此超過實際餘額。
        int orderAmount = menu.getUnitPrice() * request.getQuantity();
        long available = user.getBalance() - orderItemMapper.getFrozenAmount(userId);
        if (available < orderAmount) {
            throw new InsufficientBalanceException("餘額不足");
        }

        // 6. Insert order item with snapshot
        OrderItem item = new OrderItem();
        item.setOrderId(request.getOrderId());
        item.setUserId(userId);
        item.setMenuId(request.getMenuId());
        item.setProductName(menu.getProductName());
        item.setUnitPrice(menu.getUnitPrice());
        item.setQuantity(request.getQuantity());
        orderItemMapper.insert(item);

        if (idempotencyKey != null) {
            OrderItemIdempotencyKey keyRecord = new OrderItemIdempotencyKey();
            keyRecord.setUserId(userId);
            keyRecord.setIdempotencyKey(idempotencyKey);
            keyRecord.setItemId(item.getItemId());
            idempotencyKeyMapper.insert(keyRecord);
        }

        // 通知延到交易 commit 之後才送(見 BalanceChangeNotifier):
        // 交易若回滾,使用者不該收到「下單成功」;也讓上面那把行鎖不必等推播。
        eventPublisher.publishEvent(new BalanceChangedEvent(userId,
                "下單：" + menu.getProductName() + " x" + request.getQuantity()));
        return new CreateOrderItemResult(item.getItemId(), false);
    }

    public void deleteUserOrder(DeleteOrderItemRequest request, String userId) {
        Order order = orderMapper.selectById(request.getOrderId());
        if (order == null) {
            throw new ResourceNotFoundException("訂單不存在");
        }
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new IllegalStateException("僅 OPEN 狀態的訂單可刪除品項");
        }

        if ("all".equals(request.getItemId())) {
            if (!order.getCreatedBy().equals(userId) && !canRescue(userId)) {
                throw new ForbiddenException("無權限刪除此訂單");
            }
            // 條件式寫入:讀到 OPEN 之後,截止結算可能已經把狀態改掉了
            if (orderMapper.updateStatusIfIn(order.getOrderId(), OrderStatus.CANCELLED,
                    List.of(OrderStatus.OPEN)) == 0) {
                throw new ConflictException("訂單狀態已變更，無法刪除");
            }
            settlementQueue.remove(order.getOrderId());
        } else {
            // Delete single item
            int itemId = Integer.parseInt(request.getItemId());
            OrderItem item = orderItemMapper.selectById(itemId);
            if (item == null) {
                throw new ResourceNotFoundException("品項不存在");
            }
            // Permission check
            boolean isOwner = order.getCreatedBy().equals(userId);
            boolean isItemOwner = item.getUserId().equals(userId);
            if (!isOwner && !isItemOwner && !canRescue(userId)) {
                throw new ForbiddenException("無權限刪除此品項");
            }
            orderItemMapper.deleteById(itemId);

            // 通知品項主人(不一定是執行刪除的人)。這裡沒有交易、刪除已經 commit,
            // 交給 BalanceChangeNotifier 立即處理,推播失敗才不會讓已成功的刪除回錯誤
            eventPublisher.publishEvent(new BalanceChangedEvent(item.getUserId(),
                    "刪除品項：" + item.getProductName()));
        }
    }

    public void cancelOrder(String orderId, String userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("訂單不存在");
        }
        if (!order.getCreatedBy().equals(userId) && !canRescue(userId)) {
            throw new ForbiddenException("僅開團者、客服或超級管理員可取消訂單");
        }
        if (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.CLOSED) {
            throw new IllegalStateException("僅 OPEN 或 CLOSED 狀態的訂單可取消");
        }

        // 條件式寫入:讀到 CLOSED 之後可能已被結算,不能把 SETTLED 蓋成 CANCELLED
        if (orderMapper.updateStatusIfIn(orderId, OrderStatus.CANCELLED,
                List.of(OrderStatus.OPEN, OrderStatus.CLOSED)) == 0) {
            throw new ConflictException("訂單狀態已變更，無法取消");
        }
        settlementQueue.remove(orderId);
    }

    /**
     * Called by DelayQueue consumer when deadline arrives.
     * OPEN → CLOSED → attempt payOrder.
     * CLOSED 代表上次扣款中途出錯(狀態已改、扣款已 rollback),重試時直接接著扣款。
     */
    public void settleOrder(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null
                || (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.CLOSED)) {
            log.info("Skipping settlement for order {} (status: {})",
                    orderId, order != null ? order.getStatus() : "not found");
            return;
        }

        // OPEN → CLOSED(條件式寫入:讀到 OPEN 之後團主可能剛取消,不能把 CANCELLED 蓋回來)
        if (order.getStatus() == OrderStatus.OPEN) {
            if (orderMapper.updateStatusIfIn(orderId, OrderStatus.CLOSED, List.of(OrderStatus.OPEN)) == 0) {
                log.info("Order {} changed status before closing, skipping settlement", orderId);
                return;
            }
            order.setStatus(OrderStatus.CLOSED);
        }

        // Attempt payment(通知的寫入與推播都在 payOrder 內,手動結算走同一條路)
        try {
            payOrder(orderId);
        } catch (InsufficientBalanceException e) {
            log.warn("Settlement failed for order {}: {}", orderId, e.getMessage());
        } catch (ConflictException e) {
            // 同時有人(例如團主手動 pay_order)先結算完了,這邊不用再做
            log.info("Order {} was settled concurrently, skipping", orderId);
        }
    }

    /**
     * 自動重試次數用完時呼叫:訂單停在 OPEN 或 CLOSED,通知團主與能救援訂單的管理員手動結算。
     * 訂單若已到終態(例如結算成功後、確認前機器當掉,又被回收到次數用完),就不需要人工處理。
     */
    public void notifySettlementAbandoned(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null
                || (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.CLOSED)) {
            return;
        }
        Set<String> recipients = new LinkedHashSet<>();
        recipients.add(order.getCreatedBy());
        recipients.addAll(roleMapper.selectUserIdsByRoleNames(RESCUE_ROLES));
        // 呼叫端是 at-least-once(失敗會下一輪重送):每則寫入本身是原子的,
        // 重送時已存在的由唯一鍵擋下並略過推播 —— 上次可能已推過,沒推過的收件人查未讀也看得到
        for (String userId : recipients) {
            Notification notification;
            try {
                notification = notificationRecorder.recordAbandoned(order, userId);
            } catch (DuplicateKeyException e) {
                log.info("Abandoned notification for order {} already recorded for user {}", orderId, userId);
                continue;
            }
            pushQuietly(List.of(notification));
        }
    }

    /**
     * Pay order: CAS debit each user, write transactions, set SETTLED.
     * On insufficient balance: set FAILED (separate transaction) then throw.
     * 兩種結果的通知都在各自的交易內寫入,這裡在 commit 之後才推播。
     */
    public void payOrder(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("訂單不存在");
        }
        if (order.getStatus() == OrderStatus.SETTLED) {
            throw new ConflictException("該訂單已結算，無法進行付款");
        }
        if (order.getStatus() == OrderStatus.OPEN) {
            throw new IllegalStateException("訂單尚未截止");
        }
        if (order.getStatus() != OrderStatus.CLOSED && order.getStatus() != OrderStatus.FAILED) {
            throw new IllegalStateException("訂單狀態不允許結算");
        }

        List<Notification> written;
        try {
            written = paymentService.executePayment(order);
        } catch (InsufficientBalanceException e) {
            // 扣款交易已回滾;在另一個交易把訂單轉為 FAILED 並寫入失敗通知
            pushQuietly(paymentService.markFailed(order, e.getShortUserIds()));
            throw e;
        }
        pushQuietly(written);
        pushBalanceUpdates(order);
    }

    /**
     * 扣款已 commit:每位參與者的餘額變了(凍結也解除了),各推一次最新可用餘額。
     * 任何失敗都不能往外丟 —— 往外丟會被 consumer 當成結算失敗重排。
     */
    private void pushBalanceUpdates(Order order) {
        Set<String> participants;
        try {
            participants = orderItemMapper.selectList(
                            new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getOrderId()))
                    .stream().map(OrderItem::getUserId).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.error("Failed to load participants for balance push, order={}", order.getOrderId(), e);
            return;
        }
        for (String userId : participants) {
            try {
                User user = userMapper.selectById(userId);
                long available = user.getBalance() - orderItemMapper.getFrozenAmount(userId);
                notificationService.sendBalanceUpdate(userId, available, "結算：" + order.getOrderName());
            } catch (Exception e) {
                log.error("Failed to push balance update for user={} after settling order {}",
                        userId, order.getOrderId(), e);
            }
        }
    }

    /** 推播只是盡力送達:通知已經保存,推播失敗不能反過來影響結算結果 */
    private void pushQuietly(List<Notification> notifications) {
        for (Notification notification : notifications) {
            try {
                notificationService.pushSettlement(notification);
            } catch (Exception e) {
                log.error("Failed to push notification {} to user {}",
                        notification.getNotificationId(), notification.getUserId(), e);
            }
        }
    }
}
