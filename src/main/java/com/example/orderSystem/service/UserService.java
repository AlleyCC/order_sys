package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.response.TransactionResponse;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final TransactionMapper transactionMapper;

    public List<TransactionResponse> getTransactionRecord(String userId) {
        List<Transaction> transactions = transactionMapper.selectList(
                new LambdaQueryWrapper<Transaction>()
                        .eq(Transaction::getUserId, userId)
                        .orderByDesc(Transaction::getCreatedAt)
        );

        return transactions.stream().map(t -> {
            TransactionResponse resp = new TransactionResponse();
            resp.setTransactionId(t.getTransactionId());
            // RECHARGE = positive, DEBIT = negative
            resp.setAmount(t.getType() == TradeType.DEBIT ? -t.getAmount() : t.getAmount());
            resp.setCreatedAt(t.getCreatedAt());
            return resp;
        }).toList();
    }
}
