package com.example.orderSystem.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通道回呼。只讀單號;通道附帶的狀態、金額等欄位一律忽略,付款結果要回頭查單才算數。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayNotifyRequest {

    @NotBlank(message = "必須輸入 rechargeId")
    private String rechargeId;
}
