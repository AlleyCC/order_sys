package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.Store;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.exception.ResourceNotFoundException;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.StoreMapper;
import com.example.orderSystem.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final StoreMapper storeMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final UserMapper userMapper;

    public List<Store> getAllShops() {
        return storeMapper.selectList(null);
    }

    public List<Map<String, Object>> getAllOrders() {
        return orderMapper.selectList(null).stream().map(order -> {
            Store store = storeMapper.selectById(order.getStoreId());
            return Map.<String, Object>of(
                    "orderId", order.getOrderId(),
                    "orderName", order.getOrderName(),
                    "deadline", order.getDeadline(),
                    "minOrderAmount", store != null ? store.getMinOrderAmount() : 0
            );
        }).toList();
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

        // available = balance - SUM(OPEN + FAILED orders' subtotals)
        List<Order> frozenOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .in(Order::getStatus, OrderStatus.OPEN, OrderStatus.FAILED)
        );

        long frozenAmount = 0;
        for (Order order : frozenOrders) {
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getOrderId, order.getOrderId())
                            .eq(OrderItem::getUserId, userId)
            );
            frozenAmount += items.stream().mapToLong(OrderItem::getSubtotal).sum();
        }

        return Map.of(
                "balance", balance,
                "availableBalance", balance - frozenAmount
        );
    }
}
