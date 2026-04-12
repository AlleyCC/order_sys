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
import com.example.orderSystem.exception.*;
import com.example.orderSystem.mapper.*;
import com.example.orderSystem.scheduler.RedisSettlementQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StoreMapper storeMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final MenuMapper menuMapper;
    private final UserMapper userMapper;
    private final RedisSettlementQueue settlementQueue;
    private final PaymentService paymentService;
    private final NotificationService notificationService;

    public List<Store> getAllShops() {
        return storeMapper.selectList(null);
    }

    public IPage<Map<String, Object>> getAllOrders(int page, int size) {
        return orderMapper.getAllOrdersWithStore(new Page<>(page, size));
    }

    public OrderDetailResponse getOrderDetail(String orderId) {
        OrderDetailResponse detail = orderMapper.getOrderDetail(orderId);
        if (detail == null || detail.getOrderId() == null) {
            throw new ResourceNotFoundException("訂單不存在");
        }
        return detail;
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

        // 3. Check available balance
        int orderAmount = menu.getUnitPrice() * request.getQuantity();
        Map<String, Object> account = getUserAccount(userId);
        long available = (long) account.get("availableBalance");
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

        // Notify balance update
        Map<String, Object> updatedAccount = getUserAccount(userId);
        long updatedAvailable = (long) updatedAccount.get("availableBalance");
        notificationService.sendBalanceUpdate(userId, updatedAvailable,
                "下單：" + menu.getProductName() + " x" + request.getQuantity());
    }

    public void deleteUserOrder(DeleteOrderItemRequest request, String userId, String role) {
        Order order = orderMapper.selectById(request.getOrderId());
        if (order == null) {
            throw new ResourceNotFoundException("訂單不存在");
        }
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new IllegalStateException("僅 OPEN 狀態的訂單可刪除品項");
        }

        if ("all".equals(request.getItemId())) {
            // Cancel entire order — only owner or admin
            if (!"admin".equals(role) && !order.getCreatedBy().equals(userId)) {
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
            boolean isAdmin = "admin".equals(role);
            boolean isOwner = order.getCreatedBy().equals(userId);
            boolean isItemOwner = item.getUserId().equals(userId);
            if (!isAdmin && !isOwner && !isItemOwner) {
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

    public void cancelOrder(String orderId, String userId, String role) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new ResourceNotFoundException("訂單不存在");
        }
        if (!"admin".equals(role) && !order.getCreatedBy().equals(userId)) {
            throw new ForbiddenException("僅開團者或 admin 可取消訂單");
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
