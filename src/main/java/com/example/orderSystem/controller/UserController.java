package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.PageLimits;
import com.example.orderSystem.dto.response.NotificationResponse;
import com.example.orderSystem.dto.response.PageResponse;
import com.example.orderSystem.dto.response.TransactionResponse;
import com.example.orderSystem.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "User", description = "使用者帳戶、交易紀錄與站內通知")
public class UserController {

    private final UserService userService;

    @GetMapping("/user/get_user_transaction_record")
    @Operation(summary = "取得目前登入使用者的交易紀錄")
    public ResponseEntity<List<TransactionResponse>> getTransactionRecord(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(userService.getTransactionRecord(userId));
    }

    @GetMapping("/user/get_unread_notifications")
    @Operation(summary = "分頁取得目前登入使用者的未讀通知（新的在前）")
    public ResponseEntity<PageResponse<NotificationResponse>> getUnreadNotifications(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = PageLimits.PAGE_MESSAGE) int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = PageLimits.SIZE_MESSAGE)
            @Max(value = PageLimits.MAX_SIZE, message = PageLimits.SIZE_MESSAGE) int size) {
        return ResponseEntity.ok(PageResponse.from(
                userService.getUnreadNotifications(userId, page, size), NotificationResponse::from));
    }

    @PostMapping("/user/read_notification")
    @Operation(summary = "將自己的一則通知標為已讀（重複標記視為成功）")
    public ResponseEntity<Map<String, String>> readNotification(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        userService.markNotificationRead(userId, body.get("notificationId"));
        return ResponseEntity.ok(Map.of("message", "已標為已讀"));
    }
}
