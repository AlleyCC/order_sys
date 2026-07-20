package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 刪除分類的請求(軟刪除)。
 * 走 POST + body 而非 DELETE:HTTP spec 對 DELETE 的 request body 語意未定義,
 * 部分 proxy/client 會將其丟棄,而 version(樂觀鎖 token)必須可靠送達。
 */
@Data
public class CategoryDeleteRequest {

    @NotNull(message = "必須輸入 categoryId")
    private Integer categoryId;

    @NotNull(message = "必須輸入 version")
    private Integer version;
}
