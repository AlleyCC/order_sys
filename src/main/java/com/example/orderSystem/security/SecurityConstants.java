package com.example.orderSystem.security;

/**
 * 免登入白名單(design.md D6):集中一份,SecurityConfig permitAll 引用。
 * 白名單變動本質上伴隨程式碼變動(新公開端點),所以放常數不放 DB。
 */
public final class SecurityConstants {

    public static final String[] WHITE_LIST = {
            "/login/create_token",
            "/login/logout",
            "/auth/refresh",
            "/ws/**",
            "/pay/notify/**",           // 付款通道回呼:沒有我們的 token,內容不採信、一律回頭查單
            "/mock-channel/**",         // 模擬通道:扮演外部通道,只在 app.pay.channel=mock 時存在
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**"
    };

    private SecurityConstants() {
    }
}
