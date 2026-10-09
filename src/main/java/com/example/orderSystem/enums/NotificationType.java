package com.example.orderSystem.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum NotificationType {
    SETTLEMENT_SUCCEEDED("SETTLEMENT_SUCCEEDED"),
    SETTLEMENT_SUCCEEDED_OWNER("SETTLEMENT_SUCCEEDED_OWNER"),
    SETTLEMENT_INSUFFICIENT("SETTLEMENT_INSUFFICIENT"),
    SETTLEMENT_BLOCKED("SETTLEMENT_BLOCKED"),
    SETTLEMENT_FAILED_OWNER("SETTLEMENT_FAILED_OWNER"),
    SETTLEMENT_ABANDONED("SETTLEMENT_ABANDONED");

    @EnumValue
    private final String value;

    NotificationType(String value) {
        this.value = value;
    }
}
