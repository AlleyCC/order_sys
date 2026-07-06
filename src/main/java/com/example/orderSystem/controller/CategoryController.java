package com.example.orderSystem.controller;

import com.example.orderSystem.dto.request.CategoryRequest;
import com.example.orderSystem.dto.response.CategoryResponse;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Category", description = "分類管理")
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping("/category/create_category")
    // 授權在這裡宣告式處理:非 ADMIN 進不來,Service 完全不碰權限。
    // (需要在 SecurityConfig 開啟 @EnableMethodSecurity 才會生效)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "新增分類(限管理員,名稱須唯一)")
    public ResponseEntity<CategoryResponse> createCategory(
            @Valid @RequestBody CategoryRequest request,
            HttpServletRequest httpRequest) {
        // operator = 建立者,由 JwtAuthenticationFilter 放進 request attribute,
        // 只拿來寫 created_by 稽核欄,不是拿來授權(授權已由 @PreAuthorize 擋掉)。
        String operator = (String) httpRequest.getAttribute("userId");

        Category created = categoryService.createCategory(request, operator);

        // 成功回 201 Created + 乾淨的對外 DTO(不曝露 entity 內部欄位)
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(created));
    }
}
