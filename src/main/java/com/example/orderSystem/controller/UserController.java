package com.example.orderSystem.controller;

import com.example.orderSystem.dto.response.TransactionResponse;
import com.example.orderSystem.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/user/get_user_transaction_record")
    public ResponseEntity<List<TransactionResponse>> getTransactionRecord(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return ResponseEntity.ok(userService.getTransactionRecord(userId));
    }
}
