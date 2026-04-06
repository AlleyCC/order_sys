package com.example.orderSystem.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum TradeType {
    DEBIT("DEBIT"),
    RECHARGE("RECHARGE");

    @EnumValue
    private final String value;

    TradeType(String value) {
        this.value = value;
    }
}
