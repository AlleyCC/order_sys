package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.CreateOrderRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Set<String> RESCUE_ROLES = Set.of("SUPER_ADMIN", "CUSTOMER_SERVICE");

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

    public List<Store> getAllShops() {
        return storeMapper.selectList(null);
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
     * 之後所有一般 SELECT 都讀那個快照。本方法在鎖之前已經 select 過 order/menu,
     * 快照因此早就固定 —— 就算 selectForUpdate 拿到了鎖(它是當前讀、看得到最新),
     * 後面 getFrozenAmount 這個一般 SELECT 仍會讀到舊快照、看不見對手剛寫入的品項,
     * 鎖等於白加。READ_COMMITTED 下每個 SELECT 都讀最新已提交,鎖才真的有效。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void createUserOrder(CreateOrderItemRequest request, String userId) {
        // 1. Validate order
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

        // 2. Validate menu
        Menu menu = menuMapper.selectById(request.getMenuId());
        if (menu == null) {
            throw new ResourceNotFoundException("菜單品項不存在");
        }
        if (!menu.getIsAvailable()) {
            throw new IllegalStateException("該品項已下架");
        }

        // 3. Check available balance —— 必須鎖住後才讀,否則是 check-then-act:
        //    兩個併發請求會讀到同一份可用餘額而雙雙放行,凍結因此超過實際餘額。
        //    凍結是 order_items 上的 SUM、沒有實體可鎖,所以改鎖 users 這一行:
        //    鎖的對象不必是要保護的資料,只要是所有寫入者都必經的同一個點。
        int orderAmount = menu.getUnitPrice() * request.getQuantity();
        User user = userMapper.selectForUpdate(userId);
        if (user == null) {
            throw new ResourceNotFoundException("使用者不存在");
        }
        long available = user.getBalance() - orderItemMapper.getFrozenAmount(userId);
        if (available < orderAmount) {
            throw new InsufficientBalanceException("餘額不足");
        }

        // 4. Insert order item with snapshot
        OrderItem item = new OrderItem();
        item.setOrderId(request.getOrderId());
        item.setUserId(userId);
        item.setMenuId(request.getMenuId());
        item.setProductName(menu.getProductName());
        item.setUnitPrice(menu.getUnitPrice());
        item.setQuantity(request.getQuantity());
        orderItemMapper.insert(item);

        // 通知延到交易 commit 之後才送(見 BalanceChangeNotifier):
        // 交易若回滾,使用者不該收到「下單成功」;也讓上面那把行鎖不必等推播。
        eventPublisher.publishEvent(new BalanceChangedEvent(userId,
                "下單：" + menu.getProductName() + " x" + request.getQuantity()));
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
            order.setStatus(OrderStatus.CANCELLED);
            orderMapper.updateById(order);
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

            // Notify balance update to the item owner
            String itemOwner = item.getUserId();
            Map<String, Object> updatedAccount = getUserAccount(itemOwner);
            long updatedAvailable = (long) updatedAccount.get("availableBalance");
            notificationService.sendBalanceUpdate(itemOwner, updatedAvailable, "刪除品項：" + item.getProductName());
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

        order.setStatus(OrderStatus.CANCELLED);
        orderMapper.updateById(order);
        settlementQueue.remove(orderId);
    }

    /**
     * Called by DelayQueue consumer when deadline arrives.
     * OPEN → CLOSED → attempt payOrder.
     */
    public void settleOrder(String orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus() != OrderStatus.OPEN) {
            log.info("Skipping settlement for order {} (status: {})",
                    orderId, order != null ? order.getStatus() : "not found");
            return;
        }

        // OPEN → CLOSED
        order.setStatus(OrderStatus.CLOSED);
        orderMapper.updateById(order);

        // Attempt payment
        try {
            payOrder(orderId);
            // Notify all users of successful settlement
            notifySettlementResult(order, true);
        } catch (InsufficientBalanceException e) {
            log.warn("Settlement failed for order {}: {}", orderId, e.getMessage());
            notifySettlementResult(order, false);
        }
    }

    private void notifySettlementResult(Order order, boolean success) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getOrderId()));
        Map<String, Long> userTotals = items.stream()
                .collect(Collectors.groupingBy(
                        OrderItem::getUserId, Collectors.summingLong(OrderItem::getSubtotal)));

        for (String userId : userTotals.keySet()) {
            if (success) {
                User user = userMapper.selectById(userId);
                notificationService.sendSettlementSuccess(userId, order.getOrderId(),
                        order.getOrderName(), userTotals.get(userId).intValue(), user.getBalance());
            } else {
                notificationService.sendSettlementFailed(userId, order.getOrderId(), order.getOrderName());
            }
        }
    }

    /**
     * Pay order: CAS debit each user, write transactions, set SETTLED.
     * On insufficient balance: set FAILED (outside transaction) then throw.
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

        try {
            paymentService.executePayment(order);
        } catch (InsufficientBalanceException e) {
            // Set FAILED outside the rolled-back transaction
            order.setStatus(OrderStatus.FAILED);
            orderMapper.updateById(order);
            throw e;
        }
    }
}
