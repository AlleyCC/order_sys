package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.UpdateRoleResourcesRequest;
import com.example.orderSystem.dto.request.UpdateUserRolesRequest;
import com.example.orderSystem.dto.response.ResourceResponse;
import com.example.orderSystem.dto.response.RoleResponse;
import com.example.orderSystem.service.RbacAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RBAC 權限管理端(僅超級管理員)。
 * 授權不靠註解:/admin/rbac/** 已由 V7 登記進 resources 表且不掛任何角色,
 * 動態授權層依「超管不變量」放行超管、擋下其他人(403)。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "RbacAdmin", description = "RBAC 權限管理(僅超級管理員)")
public class RbacAdminController {

    private final RbacAdminService rbacAdminService;

    @GetMapping("/admin/rbac/get_roles")
    @Operation(summary = "列出所有角色")
    public ResponseEntity<List<RoleResponse>> getRoles() {
        return ResponseEntity.ok(
                rbacAdminService.listRoles().stream().map(RoleResponse::from).toList());
    }

    @GetMapping("/admin/rbac/get_resources")
    @Operation(summary = "列出所有已登記資源")
    public ResponseEntity<List<ResourceResponse>> getResources() {
        return ResponseEntity.ok(
                rbacAdminService.listResources().stream().map(ResourceResponse::from).toList());
    }

    @GetMapping("/admin/rbac/get_role_resources")
    @Operation(summary = "查某角色目前被授權的資源清單")
    public ResponseEntity<List<ResourceResponse>> getRoleResources(@RequestParam String roleName) {
        return ResponseEntity.ok(
                rbacAdminService.getRoleResources(roleName).stream().map(ResourceResponse::from).toList());
    }

    @PostMapping("/admin/rbac/update_role_resources")
    @Operation(summary = "全量替換某角色被授權的資源(寫入後即時生效)")
    public ResponseEntity<Map<String, String>> updateRoleResources(
            @Valid @RequestBody UpdateRoleResourcesRequest request) {
        rbacAdminService.updateRoleResources(request.getRoleName(), request.getResourceIds());
        return ResponseEntity.ok(Map.of("message", "角色資源已更新"));
    }

    @PostMapping("/admin/rbac/update_user_roles")
    @Operation(summary = "全量替換某使用者擁有的角色(寫入後即時生效)")
    public ResponseEntity<Map<String, String>> updateUserRoles(
            @Valid @RequestBody UpdateUserRolesRequest request) {
        rbacAdminService.updateUserRoles(request.getUserId(), request.getRoleNames());
        return ResponseEntity.ok(Map.of("message", "使用者角色已更新"));
    }
}
