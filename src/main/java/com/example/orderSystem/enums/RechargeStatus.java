package com.example.orderSystem.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum RechargeStatus {
    WAITING("WAITING"),
    SUCCESS("SUCCESS"),
    CLOSED("CLOSED");

    @EnumValue
    private final String value;

    RechargeStatus(String value) {
        this.value = value;
    }
}
