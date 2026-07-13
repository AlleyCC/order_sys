package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.request.CategoryRequest;
import com.example.orderSystem.dto.request.CategoryUpdateRequest;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.exception.ConflictException;
import com.example.orderSystem.exception.ResourceNotFoundException;
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
        category.setVersion(0);            // 樂觀鎖起始版本;讓回應也帶得出 version=0
        category.setIsDeleted(0);
        category.setCreatedBy(operator);
        categoryMapper.insert(category);
        return category;
    }

    /**
     * 修改分類名稱/排序(PATCH 部分更新)。授權(限管理員)在 Controller 處理。
     * 併發用樂觀鎖:request.version 是「client 讀到當下的版本」,updateById 時交給
     * OptimisticLockerInnerInterceptor 比對,打中 0 列即代表有人搶先改過 → 409。
     */
    public Category updateCategory(CategoryUpdateRequest request, String operator) {
        // 1. 只找「有效」分類;不存在或已軟刪 → 對外一律 404(不洩漏已刪資料)
        Category existing = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getCategoryId, request.getCategoryId())
                .eq(Category::getIsDeleted, 0));
        if (existing == null) {
            throw new ResourceNotFoundException("分類不存在");
        }

        // 2. 要改名、且新名字跟現值不同時,才檢查是否撞到「別的」有效分類(排除自己)。
        //    改成跟自己一樣的名字不算撞名。DB 的 uk_name_active 是併發下的最後防線。
        if (request.getName() != null && !request.getName().equals(existing.getName())) {
            Long dup = categoryMapper.selectCount(new LambdaQueryWrapper<Category>()
                    .eq(Category::getName, request.getName())
                    .eq(Category::getIsDeleted, 0)
                    .ne(Category::getCategoryId, request.getCategoryId()));
            if (dup != null && dup > 0) {
                throw new ConflictException("分類名稱已存在");
            }
        }

        // 3. 組更新用 entity:只帶「有送」的欄位(null 欄位被 MyBatis-Plus 預設忽略 → 天然部分更新)。
        //    version 一定要帶,樂觀鎖攔截器靠它組 WHERE。
        Category update = new Category();
        update.setCategoryId(request.getCategoryId());
        update.setVersion(request.getVersion());
        update.setUpdatedBy(operator);
        if (request.getName() != null) {
            update.setName(request.getName());
        }
        if (request.getSortOrder() != null) {
            update.setSortOrder(request.getSortOrder());
        }

        // 4. 存在性剛才查過了,所以這裡 0 列 = version 對不上(有人搶先改)→ 版本衝突 409。
        int affected = categoryMapper.updateById(update);
        if (affected == 0) {
            throw new ConflictException("分類已被其他人修改,請重新載入");
        }

        // 5. 重查完整最新狀態(version 已 +1、未改欄位維持原值)回給對外 DTO。
        return categoryMapper.selectById(request.getCategoryId());
    }
}
