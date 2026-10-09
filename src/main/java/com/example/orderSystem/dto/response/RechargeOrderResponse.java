package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.enums.RechargeStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RechargeOrderResponse {

    private String rechargeId;
    private String userId;
    private Integer amount;
    private RechargeStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;

    /** 付款資訊(例如付款頁網址),只在建立儲值單時回傳 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String payInfo;

    public static RechargeOrderResponse from(RechargeOrder order) {
        RechargeOrderResponse resp = new RechargeOrderResponse();
        resp.setRechargeId(order.getRechargeId());
        resp.setUserId(order.getUserId());
        resp.setAmount(order.getAmount());
        resp.setStatus(order.getStatus());
        resp.setCreatedAt(order.getCreatedAt());
        resp.setExpireAt(order.getExpireAt());
        resp.setPaidAt(order.getPaidAt());
        return resp;
    }
}
