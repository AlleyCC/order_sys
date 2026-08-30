package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.orderSystem.dto.request.CategoryDeleteRequest;
import com.example.orderSystem.dto.request.CategoryRequest;
import com.example.orderSystem.dto.request.CategoryUpdateRequest;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.exception.BadRequestException;
import com.example.orderSystem.exception.ConflictException;
import com.example.orderSystem.exception.ResourceNotFoundException;
import com.example.orderSystem.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;

    /**
     * 新增分類。授權由動態授權層(resources 表登記)在進入 Controller 前處理,
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

    /**
     * 刪除分類(軟刪除:is_deleted 0→1)。授權(限管理員)在 Controller 處理。
     * 併發同樣走樂觀鎖:B 搶先改過(version +1)→ A 帶舊 version 來刪會打中 0 列 → 409,
     * 逼 client 重新載入、看清楚現在的分類長什麼樣再決定要不要刪。
     *
     * 「分類下有商品不可刪」的檢查目前刻意缺席:menus 還沒有 category_id,
     * 無從查起。等「商品掛分類」功能落地時必須回來補(見 category-followups)。
     */
    public void deleteCategory(CategoryDeleteRequest request, String operator) {
        // 1. 只找「有效」分類;不存在或已軟刪 → 一律 404(與 update 一致,不洩漏已刪資料)。
        //    先做這步,第 2 步打中 0 列時才能斷定原因只剩 version 衝突。
        Category existing = categoryMapper.selectOne(new LambdaQueryWrapper<Category>()
                .eq(Category::getCategoryId, request.getCategoryId())
                .eq(Category::getIsDeleted, 0));
        if (existing == null) {
            throw new ResourceNotFoundException("分類不存在");
        }

        // 2. 軟刪 = 一次「只改 is_deleted」的部分更新;name/sortOrder 不帶,絕不順手動到。
        //    active_flag 是衍生欄位會自動變 NULL → 同名分類從此可重建(uk_name_active 設計)。
        Category update = new Category();
        update.setCategoryId(request.getCategoryId());
        update.setVersion(request.getVersion());
        update.setIsDeleted(1);
        update.setUpdatedBy(operator);

        int affected = categoryMapper.updateById(update);
        if (affected == 0) {
            throw new ConflictException("分類已被其他人修改,請重新載入");
        }
    }

    // 分頁上限:防止 client 用超大 size 一次撈全表,架空分頁的意義。
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 查詢分類(任何登入者)。categoryId / categoryName 擇一過濾,都不帶 = 查全部。
     * name 是精確比對(前端流程是先撈清單再用 id 查,模糊查詢目前不需要)。
     * 查無資料回空頁(200),不是 404 —— 列表端點「沒有符合的資料」是正常結果。
     */
    public IPage<Category> getCategories(Integer categoryId, String categoryName, int page, int size) {
        // 空白字串視同沒帶:query string 很容易出現 ?categoryName= 這種殘留參數
        String name = StringUtils.hasText(categoryName) ? categoryName.trim() : null;

        if (categoryId != null && name != null) {
            throw new BadRequestException("categoryId 與 categoryName 不可同時使用");
        }
        if (page < 1) {
            throw new BadRequestException("page 必須大於等於 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size 必須介於 1 到 " + MAX_PAGE_SIZE);
        }

        // 排序必須「完全決定順序」:sortOrder 可能同值,要再用 categoryId 補穩定性,
        // 否則翻頁時 DB 可以自由重排同值資料 → 跨頁重複/遺漏。
        LambdaQueryWrapper<Category> query = new LambdaQueryWrapper<Category>()
                .eq(Category::getIsDeleted, 0)
                .eq(categoryId != null, Category::getCategoryId, categoryId)
                .eq(name != null, Category::getName, name)
                .orderByAsc(Category::getSortOrder)
                .orderByAsc(Category::getCategoryId);

        return categoryMapper.selectPage(new Page<>(page, size), query);
    }
}
