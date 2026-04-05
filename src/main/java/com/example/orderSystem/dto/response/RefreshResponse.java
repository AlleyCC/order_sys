package com.example.orderSystem.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RefreshResponse {

    private String accessToken;
    private long expiresIn;
}
