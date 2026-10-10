package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.PageLimits;
import com.example.orderSystem.dto.response.AdminTransactionResponse;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.dto.response.PageResponse;
import com.example.orderSystem.dto.response.RechargeOrderResponse;
import com.example.orderSystem.dto.response.TransactionResponse;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.service.OrderService;
import com.example.orderSystem.service.RechargeService;
import com.example.orderSystem.service.TransactionAuditService;
import com.example.orderSystem.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Admin Query", description = "後台查詢與對帳（客服、會計）")
public class AdminQueryController {

    private final OrderService orderService;
    private final UserService userService;
    private final TransactionAuditService transactionAuditService;
    private final RechargeService rechargeService;

    @GetMapping("/admin/orders/get_all_orders")
    @Operation(summary = "分頁取得全系統訂單（客服）")
    public ResponseEntity<?> getAllOrders(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = PageLimits.PAGE_MESSAGE) int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = PageLimits.SIZE_MESSAGE)
            @Max(value = PageLimits.MAX_SIZE, message = PageLimits.SIZE_MESSAGE) int size) {
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

    @GetMapping("/admin/transactions/get_all_transactions")
    @Operation(summary = "分頁取得全體使用者的交易紀錄,可依類型、使用者、日期區間篩選（會計）")
    public ResponseEntity<PageResponse<AdminTransactionResponse>> getAllTransactions(
            @RequestParam(required = false) TradeType type,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = PageLimits.PAGE_MESSAGE) int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = PageLimits.SIZE_MESSAGE)
            @Max(value = PageLimits.MAX_SIZE, message = PageLimits.SIZE_MESSAGE) int size) {
        return ResponseEntity.ok(transactionAuditService.getAllTransactions(type, userId, from, to, page, size));
    }

    @GetMapping("/admin/transactions/get_recharge_orders")
    @Operation(summary = "分頁取得儲值單,可依狀態、使用者、日期區間篩選（會計）")
    public ResponseEntity<PageResponse<RechargeOrderResponse>> getRechargeOrders(
            @RequestParam(required = false) RechargeStatus status,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = PageLimits.PAGE_MESSAGE) int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = PageLimits.SIZE_MESSAGE)
            @Max(value = PageLimits.MAX_SIZE, message = PageLimits.SIZE_MESSAGE) int size) {
        return ResponseEntity.ok(transactionAuditService.getRechargeOrders(status, userId, from, to, page, size));
    }

    @PostMapping("/admin/transactions/sync_recharge_order")
    @Operation(summary = "人工對單:重新向通道查詢儲值單,已付款即入帳(已關閉的單也可)（會計）")
    public ResponseEntity<RechargeOrderResponse> syncRechargeOrder(
            @RequestParam String rechargeId,
            @AuthenticationPrincipal String operator) {
        return ResponseEntity.ok(rechargeService.manualSync(rechargeId, operator));
    }
}
