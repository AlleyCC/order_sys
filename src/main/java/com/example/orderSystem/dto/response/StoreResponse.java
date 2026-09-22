package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.Store;

public record StoreResponse(String storeId, String storeName, Integer minOrderAmount) {

    public static StoreResponse from(Store s) {
        return new StoreResponse(s.getStoreId(), s.getStoreName(), s.getMinOrderAmount());
    }
}
