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

        settlementQueue.add(order.getOrderId(), order.getDeadline());

        return Map.of("orderId", order.getOrderId(), "storeId", order.getStoreId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreateOrderItemResult createUserOrder(CreateOrderItemRequest request, String userId,
                                                 String idempotencyKey) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("Idempotency-Key 格式錯誤:限 1 到 64 個英數字、- 或 _");
        }

        User user = userMapper.selectForUpdate(userId);
        if (user == null) {
            throw new ResourceNotFoundException("使用者不存在");
        }

        if (idempotencyKey != null) {
            OrderItemIdempotencyKey existing = idempotencyKeyMapper.selectOne(
                    new LambdaQueryWrapper<OrderItemIdempotencyKey>()
                            .eq(OrderItemIdempotencyKey::getUserId, userId)
                            .eq(OrderItemIdempotencyKey::getIdempotencyKey, idempotencyKey));
            if (existing != null) {
                return new CreateOrderItemResult(existing.getItemId(), true);
            }
        }

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

        Menu menu = menuMapper.selectById(request.getMenuId());
        if (menu == null) {
            throw new ResourceNotFoundException("菜單品項不存在");
        }
        if (!menu.getIsAvailable()) {
            throw new IllegalStateException("該品項已下架");
        }

        int orderAmount = menu.getUnitPrice() * request.getQuantity();
        long available = user.getBalance() - orderItemMapper.getFrozenAmount(userId);
        if (available < orderAmount) {
            throw new InsufficientBalanceException("餘額不足");
        }

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
            if (orderMapper.updateStatusIfIn(order.getOrderId(), OrderStatus.CANCELLED,
                    List.of(OrderStatus.OPEN)) == 0) {
                throw new ConflictException("訂單狀態已變更，無法刪除");
            }
            settlementQueue.remove(order.getOrderId());
        } else {
            int itemId = Integer.parseInt(request.getItemId());
            OrderItem item = orderItemMapper.selectById(itemId);
            if (item == null) {
                throw new ResourceNotFoundException("品項不存在");
            }
            boolean isOwner = order.getCreatedBy().equals(userId);
            boolean isItemOwner = item.getUserId().equals(userId);
            if (!isOwner && !isItemOwner && !canRescue(userId)) {
                throw new ForbiddenException("無權限刪除此品項");
            }
            orderItemMapper.deleteById(itemId);

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

        if (orderMapper.updateStatusIfIn(orderId, OrderStatus.CANCELLED,
                List.of(OrderStatus.OPEN, OrderStatus.CLOSED)) == 0) {
            throw new ConflictException("訂單狀態已變更，無法取消");
        }
        settlementQueue.remove(orderId);
    }

    public void settleOrder(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null
                || (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.CLOSED)) {
            log.info("Skipping settlement for order {} (status: {})",
                    orderId, order != null ? order.getStatus() : "not found");
            return;
        }

        if (order.getStatus() == OrderStatus.OPEN) {
            if (orderMapper.updateStatusIfIn(orderId, OrderStatus.CLOSED, List.of(OrderStatus.OPEN)) == 0) {
                log.info("Order {} changed status before closing, skipping settlement", orderId);
                return;
            }
            order.setStatus(OrderStatus.CLOSED);
        }

        try {
            payOrder(orderId);
        } catch (InsufficientBalanceException e) {
            log.warn("Settlement failed for order {}: {}", orderId, e.getMessage());
        } catch (ConflictException e) {
            log.info("Order {} was settled concurrently, skipping", orderId);
        }
    }

    public void notifySettlementAbandoned(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null
                || (order.getStatus() != OrderStatus.OPEN && order.getStatus() != OrderStatus.CLOSED)) {
            return;
        }
        Set<String> recipients = new LinkedHashSet<>();
        recipients.add(order.getCreatedBy());
        recipients.addAll(roleMapper.selectUserIdsByRoleNames(RESCUE_ROLES));
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
            pushQuietly(paymentService.markFailed(order, e.getShortUserIds()));
            throw e;
        }
        pushQuietly(written);
        pushBalanceUpdates(order);
    }

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
            eventPublisher.publishEvent(new BalanceChangedEvent(userId, "結算：" + order.getOrderName()));
        }
    }

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
