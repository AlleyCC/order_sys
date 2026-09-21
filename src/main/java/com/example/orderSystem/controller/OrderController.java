package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.CreateOrderRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.Store;
import com.example.orderSystem.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Order", description = "團購訂單建立、查詢、扣款、取消")
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/order/get_all_shops")
    @SecurityRequirements
    @Operation(summary = "取得所有可開團的店家清單（公開端點）")
    public ResponseEntity<List<Store>> getAllShops() {
        return ResponseEntity.ok(orderService.getAllShops());
    }

    @GetMapping("/order/get_all_orders")
    @Operation(summary = "分頁取得可跟團清單（僅 OPEN 狀態，摘要欄位）")
    public ResponseEntity<?> getAllOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(orderService.getOpenOrders(page, size));
    }

    @GetMapping("/order/get_order_detail")
    @Operation(summary = "取得單一訂單明細（限該訂單的參與者）")
    public ResponseEntity<OrderDetailResponse> getOrderDetail(
            @RequestParam String orderId,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(orderService.getOrderDetail(orderId, userId));
    }

    @GetMapping("/order/get_user_account")
    @Operation(summary = "取得自己的帳戶資訊（餘額、可用餘額）")
    public ResponseEntity<Map<String, Object>> getUserAccount(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(orderService.getUserAccount(userId));
    }

    @PostMapping("/order/create_order")
    @Operation(summary = "建立新團購訂單")
    public ResponseEntity<Map<String, String>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request, userId));
    }

    @PostMapping("/order/create_user_order")
    @Operation(summary = "加入已開啟的團購訂單（下單品項）")
    public ResponseEntity<Map<String, String>> createUserOrder(
            @Valid @RequestBody CreateOrderItemRequest request,
            @AuthenticationPrincipal String userId) {
        orderService.createUserOrder(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "下單成功"));
    }

    @PostMapping("/order/delete_user_order")
    @Operation(summary = "刪除品項（自己的品項、自己團內的品項；客服與超管可跨團刪除）")
    public ResponseEntity<Map<String, String>> deleteUserOrder(
            @Valid @RequestBody DeleteOrderItemRequest request,
            @AuthenticationPrincipal String userId) {
        orderService.deleteUserOrder(request, userId);
        return ResponseEntity.ok(Map.of("message", "刪除成功"));
    }

    @PostMapping("/order/cancel_order")
    @Operation(summary = "取消整筆訂單（限開團者、客服或超級管理員）")
    public ResponseEntity<Map<String, String>> cancelOrder(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal String userId) {
        orderService.cancelOrder(body.get("orderId"), userId);
        return ResponseEntity.ok(Map.of("message", "訂單已取消"));
    }

    @PostMapping("/order/pay_order")
    @Operation(summary = "結算訂單並對參與者扣款")
    public ResponseEntity<Map<String, String>> payOrder(@RequestBody Map<String, String> body) {
        orderService.payOrder(body.get("orderId"));
        return ResponseEntity.ok(Map.of("message", "扣款成功"));
    }
}
