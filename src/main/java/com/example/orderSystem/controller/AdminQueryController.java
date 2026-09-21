package com.example.orderSystem.controller;

import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.dto.response.TransactionResponse;
import com.example.orderSystem.service.OrderService;
import com.example.orderSystem.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Admin Query", description = "後台唯讀查詢（客服、會計）")
public class AdminQueryController {

    private final OrderService orderService;
    private final UserService userService;

    @GetMapping("/admin/orders/get_all_orders")
    @Operation(summary = "分頁取得全系統訂單（客服）")
    public ResponseEntity<?> getAllOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(orderService.getAllOrdersUnscoped(page, size));
    }

    @GetMapping("/admin/orders/get_order_detail")
    @Operation(summary = "取得任一訂單明細（客服）")
    public ResponseEntity<OrderDetailResponse> getOrderDetail(@RequestParam String orderId) {
        return ResponseEntity.ok(orderService.getOrderDetailUnscoped(orderId));
    }

    @GetMapping("/admin/transactions/get_user_transaction_record")
    @Operation(summary = "取得指定使用者的交易紀錄（會計）")
    public ResponseEntity<List<TransactionResponse>> getUserTransactionRecord(
            @RequestParam String userId) {
        return ResponseEntity.ok(userService.getTransactionRecord(userId));
    }
}
