package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.Resource;

public record ResourceResponse(Long resourceId, String urlPattern, String httpMethod,
                               String name, String category) {

    public static ResourceResponse from(Resource r) {
        return new ResourceResponse(r.getResourceId(), r.getUrlPattern(), r.getHttpMethod(),
                r.getName(), r.getCategory());
    }
}
