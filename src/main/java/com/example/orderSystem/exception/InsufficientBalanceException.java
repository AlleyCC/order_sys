package com.example.orderSystem.exception;

import java.util.List;

public class InsufficientBalanceException extends RuntimeException {

    /** 結算時所有餘額不足的參與者;下單時的單人檢查為空清單 */
    private final List<String> shortUserIds;

    public InsufficientBalanceException(String message) {
        super(message);
        this.shortUserIds = List.of();
    }

    public InsufficientBalanceException(List<String> shortUserIds) {
        super("餘額不足:" + String.join(", ", shortUserIds));
        this.shortUserIds = List.copyOf(shortUserIds);
    }

    public List<String> getShortUserIds() {
        return shortUserIds;
    }
}
