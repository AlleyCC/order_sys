package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.orderSystem.dto.response.AdminTransactionResponse;
import com.example.orderSystem.dto.response.PageResponse;
import com.example.orderSystem.dto.response.RechargeOrderResponse;
import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.mapper.RechargeOrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 會計對帳查詢。日期區間的起訖日都包含在內:to 當天 23:59 的紀錄也算,
 * 所以上界用「to 的隔天 00:00」且不含等號。
 */
@Service
@RequiredArgsConstructor
public class TransactionAuditService {

    private final TransactionMapper transactionMapper;
    private final RechargeOrderMapper rechargeOrderMapper;

    public PageResponse<AdminTransactionResponse> getAllTransactions(TradeType type, String userId,
                                                                     LocalDate from, LocalDate to,
                                                                     int page, int size) {
        LambdaQueryWrapper<Transaction> query = new LambdaQueryWrapper<Transaction>()
                .eq(type != null, Transaction::getType, type)
                .eq(userId != null, Transaction::getUserId, userId)
                .ge(from != null, Transaction::getCreatedAt, startOf(from))
                .lt(to != null, Transaction::getCreatedAt, startOfNextDay(to))
                .orderByDesc(Transaction::getCreatedAt)
                .orderByDesc(Transaction::getTransactionId);    // 同一秒的紀錄翻頁時順序固定
        return PageResponse.from(transactionMapper.selectPage(new Page<>(page, size), query),
                AdminTransactionResponse::from);
    }

    public PageResponse<RechargeOrderResponse> getRechargeOrders(RechargeStatus status, String userId,
                                                                 LocalDate from, LocalDate to,
                                                                 int page, int size) {
        LambdaQueryWrapper<RechargeOrder> query = new LambdaQueryWrapper<RechargeOrder>()
                .eq(status != null, RechargeOrder::getStatus, status)
                .eq(userId != null, RechargeOrder::getUserId, userId)
                .ge(from != null, RechargeOrder::getCreatedAt, startOf(from))
                .lt(to != null, RechargeOrder::getCreatedAt, startOfNextDay(to))
                .orderByDesc(RechargeOrder::getCreatedAt)
                .orderByDesc(RechargeOrder::getRechargeId);
        return PageResponse.from(rechargeOrderMapper.selectPage(new Page<>(page, size), query),
                RechargeOrderResponse::from);
    }

    private static LocalDateTime startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private static LocalDateTime startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }
}
