package com.example.orderSystem.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum OrderStatus {
    OPEN("OPEN"),
    CLOSED("CLOSED"),
    SETTLED("SETTLED"),
    CANCELLED("CANCELLED"),
    FAILED("FAILED");

    @EnumValue
    private final String value;

    OrderStatus(String value) {
        this.value = value;
    }
}
