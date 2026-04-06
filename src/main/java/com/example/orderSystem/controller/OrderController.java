package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.CreateOrderRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.Store;
import com.example.orderSystem.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/order/create_order")
    public ResponseEntity<Map<String, String>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request, userId));
    }

    @PostMapping("/order/create_user_order")
    public ResponseEntity<Map<String, String>> createUserOrder(
            @Valid @RequestBody CreateOrderItemRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        orderService.createUserOrder(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "下單成功"));
    }

    @PostMapping("/order/delete_user_order")
    public ResponseEntity<Map<String, String>> deleteUserOrder(
            @Valid @RequestBody DeleteOrderItemRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String role = auth.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "").toLowerCase();
        orderService.deleteUserOrder(request, userId, role);
        return ResponseEntity.ok(Map.of("message", "刪除成功"));
    }

    @PostMapping("/order/cancel_order")
    public ResponseEntity<Map<String, String>> cancelOrder(
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String role = auth.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "").toLowerCase();
        orderService.cancelOrder(body.get("orderId"), userId, role);
        return ResponseEntity.ok(Map.of("message", "訂單已取消"));
    }

    @PostMapping("/order/pay_order")
    public ResponseEntity<Map<String, String>> payOrder(@RequestBody Map<String, String> body) {
        orderService.payOrder(body.get("orderId"));
        return ResponseEntity.ok(Map.of("message", "扣款成功"));
    }
}
