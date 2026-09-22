package com.example.orderSystem.dto.request;

public final class PageLimits {

    public static final int MAX_SIZE = 20;

    public static final String PAGE_MESSAGE = "page 必須大於等於 1";
    public static final String SIZE_MESSAGE = "size 必須介於 1 到 " + MAX_SIZE;

    private PageLimits() {
    }
}
