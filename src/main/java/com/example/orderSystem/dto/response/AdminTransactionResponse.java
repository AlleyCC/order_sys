package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.TradeType;
import lombok.Data;

import java.time.LocalDateTime;

/** 會計對帳用的交易紀錄。金額帶正負號:儲值為正、扣款為負。 */
@Data
public class AdminTransactionResponse {

    private String transactionId;
    private String userId;
    private TradeType type;
    private Integer amount;
    private Integer closingBalance;
    private String orderId;
    private String rechargeId;
    private String createdBy;
    private LocalDateTime createdAt;

    public static AdminTransactionResponse from(Transaction t) {
        AdminTransactionResponse resp = new AdminTransactionResponse();
        resp.setTransactionId(t.getTransactionId());
        resp.setUserId(t.getUserId());
        resp.setType(t.getType());
        resp.setAmount(t.getType() == TradeType.DEBIT ? -t.getAmount() : t.getAmount());
        resp.setClosingBalance(t.getClosingBalance());
        resp.setOrderId(t.getOrderId());
        resp.setRechargeId(t.getRechargeId());
        resp.setCreatedBy(t.getCreatedBy());
        resp.setCreatedAt(t.getCreatedAt());
        return resp;
    }
}
