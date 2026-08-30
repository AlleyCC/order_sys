package com.example.orderSystem.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.orderSystem.dto.request.CategoryDeleteRequest;
import com.example.orderSystem.dto.request.CategoryRequest;
import com.example.orderSystem.dto.request.CategoryUpdateRequest;
import com.example.orderSystem.dto.response.CategoryResponse;
import com.example.orderSystem.dto.response.PageResponse;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
            @AuthenticationPrincipal String operator) {
        // operator = 建立者(Spring Security principal),只拿來寫 created_by 稽核欄,
        // 不是拿來授權(授權已由 @PreAuthorize 擋掉)。
        Category created = categoryService.createCategory(request, operator);

        // 成功回 201 Created + 乾淨的對外 DTO(不曝露 entity 內部欄位)
        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryResponse.from(created));
    }

    @PatchMapping("/category/update_category")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "修改分類名稱/排序(限管理員,樂觀鎖防併發)")
    public ResponseEntity<CategoryResponse> updateCategory(
            @Valid @RequestBody CategoryUpdateRequest request,
            HttpServletRequest httpRequest) {
        String operator = (String) httpRequest.getAttribute("userId");

        Category updated = categoryService.updateCategory(request, operator);

        // 成功回 200 + 帶最新 version 的 DTO,前端下次修改可直接沿用
        return ResponseEntity.ok(CategoryResponse.from(updated));
    }

    @PostMapping("/category/delete_category")
    @PreAuthorize("hasRole('ADMIN')")
    // 用 POST 而非 DELETE:body 要帶 version(樂觀鎖 token),
    // 而 HTTP spec 對 DELETE 帶 body 語意未定義,部分 proxy/client 會丟棄。
    @Operation(summary = "刪除分類(限管理員,軟刪除,樂觀鎖防併發)")
    public ResponseEntity<Map<String, String>> deleteCategory(
            @Valid @RequestBody CategoryDeleteRequest request,
            HttpServletRequest httpRequest) {
        String operator = (String) httpRequest.getAttribute("userId");

        categoryService.deleteCategory(request, operator);

        // 刪掉的資源沒有新狀態可回,回 200 + 訊息(專案慣例,同 create_order 風格)
        return ResponseEntity.ok(Map.of("message", "成功刪除一筆分類"));
    }

    @GetMapping("/category/get_categories")
    // 刻意沒有 @PreAuthorize:任何登入帳號都可查(未登入被 JwtAuthenticationFilter 擋 401)
    @Operation(summary = "查詢分類(分頁;categoryId 或 categoryName 擇一過濾,都不帶查全部)")
    public ResponseEntity<PageResponse<CategoryResponse>> getCategories(
            @RequestParam(required = false) Integer categoryId,
            @RequestParam(required = false) String categoryName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        IPage<Category> result = categoryService.getCategories(categoryId, categoryName, page, size);
        return ResponseEntity.ok(PageResponse.from(result, CategoryResponse::from));
    }
}
