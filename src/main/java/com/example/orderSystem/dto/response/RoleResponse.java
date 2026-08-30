package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.Role;

public record RoleResponse(Long roleId, String name, String description, Integer status) {

    public static RoleResponse from(Role r) {
        return new RoleResponse(r.getRoleId(), r.getName(), r.getDescription(), r.getStatus());
    }
}
