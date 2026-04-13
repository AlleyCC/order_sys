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
    @Operation(summary = "分頁取得訂單列表")
    public ResponseEntity<?> getAllOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(orderService.getAllOrders(page, size));
    }

    @GetMapping("/order/get_order_detail")
    @Operation(summary = "取得單一訂單明細（品項、參與者、狀態）")
    public ResponseEntity<OrderDetailResponse> getOrderDetail(
            @RequestParam String orderId) {
        return ResponseEntity.ok(orderService.getOrderDetail(orderId));
    }

    @GetMapping("/order/get_user_account")
    @Operation(summary = "取得指定使用者的帳戶資訊（餘額等）")
    public ResponseEntity<Map<String, Object>> getUserAccount(
            @RequestParam String userId) {
        return ResponseEntity.ok(orderService.getUserAccount(userId));
    }

    @PostMapping("/order/create_order")
    @Operation(summary = "建立新團購訂單")
    public ResponseEntity<Map<String, String>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request, userId));
    }

    @PostMapping("/order/create_user_order")
    @Operation(summary = "加入已開啟的團購訂單（下單品項）")
    public ResponseEntity<Map<String, String>> createUserOrder(
            @Valid @RequestBody CreateOrderItemRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        orderService.createUserOrder(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", "下單成功"));
    }

    @PostMapping("/order/delete_user_order")
    @Operation(summary = "刪除自己的品項；管理員可刪除他人")
    public ResponseEntity<Map<String, String>> deleteUserOrder(
            @Valid @RequestBody DeleteOrderItemRequest request,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        orderService.deleteUserOrder(request, userId, getCurrentRole());
        return ResponseEntity.ok(Map.of("message", "刪除成功"));
    }

    @PostMapping("/order/cancel_order")
    @Operation(summary = "取消整筆訂單（限訂單發起人或管理員）")
    public ResponseEntity<Map<String, String>> cancelOrder(
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        orderService.cancelOrder(body.get("orderId"), userId, getCurrentRole());
        return ResponseEntity.ok(Map.of("message", "訂單已取消"));
    }

    @PostMapping("/order/pay_order")
    @Operation(summary = "結算訂單並對參與者扣款")
    public ResponseEntity<Map<String, String>> payOrder(@RequestBody Map<String, String> body) {
        orderService.payOrder(body.get("orderId"));
        return ResponseEntity.ok(Map.of("message", "扣款成功"));
    }

    private String getCurrentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().isEmpty()) {
            return "employee";
        }
        return auth.getAuthorities().iterator().next().getAuthority()
                .replace("ROLE_", "").toLowerCase();
    }
}
