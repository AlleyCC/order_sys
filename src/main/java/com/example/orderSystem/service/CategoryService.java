package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.request.CategoryRequest;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.exception.ConflictException;
import com.example.orderSystem.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;

    /**
     * 新增分類。授權(限管理員)在 Controller 用 @PreAuthorize 處理,
     * 這裡只負責業務邏輯。operator 為操作者帳號,寫入稽核欄位。
     */
    public Category createCategory(CategoryRequest request, String operator) {
        // 1. 檢查同名「有效」分類是否存在 → 有就丟 ConflictException
        Long count = categoryMapper.selectCount(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, request.getName())
                .eq(Category::getIsDeleted, 0));
        if (count != null && count > 0) {
            throw new ConflictException("分類名稱已存在");
        }
        // 2. 組 Category、寫入 createdBy=operator
        Category category = new Category();
        category.setName(request.getName());
        category.setSortOrder(request.getSortOrder());
        category.setIsDeleted(0);
        category.setCreatedBy(operator);
        categoryMapper.insert(category);
        return category;
    }
}
