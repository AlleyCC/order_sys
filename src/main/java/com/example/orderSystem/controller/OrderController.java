package com.example.orderSystem.controller;

import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.Store;
import com.example.orderSystem.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/order/get_all_shops")
    public ResponseEntity<List<Store>> getAllShops() {
        return ResponseEntity.ok(orderService.getAllShops());
    }

    @GetMapping("/order/get_all_orders")
    public ResponseEntity<List<Map<String, Object>>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @GetMapping("/order/get_order_detail")
    public ResponseEntity<OrderDetailResponse> getOrderDetail(
            @RequestParam String orderId) {
        return ResponseEntity.ok(orderService.getOrderDetail(orderId));
    }

    @GetMapping("/order/get_user_account")
    public ResponseEntity<Map<String, Object>> getUserAccount(
            @RequestParam String userId) {
        return ResponseEntity.ok(orderService.getUserAccount(userId));
    }
}
