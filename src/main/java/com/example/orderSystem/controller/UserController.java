package com.example.orderSystem.controller;

import com.example.orderSystem.dto.response.TransactionResponse;
import com.example.orderSystem.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "User", description = "使用者帳戶與交易紀錄")
public class UserController {

    private final UserService userService;

    @GetMapping("/user/get_user_transaction_record")
    @Operation(summary = "取得目前登入使用者的交易紀錄")
    public ResponseEntity<List<TransactionResponse>> getTransactionRecord(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ResponseEntity.ok(userService.getTransactionRecord(userId));
    }
}
