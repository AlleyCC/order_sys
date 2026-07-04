package com.example.orderSystem.service;

import com.example.orderSystem.dto.request.CategoryRequest;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.exception.ConflictException;
import com.example.orderSystem.mapper.CategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @InjectMocks
    private CategoryService categoryService;   // 被測對象

    @Mock
    private CategoryMapper categoryMapper;      // 它的依賴,假的

    private CategoryRequest categoryReq(String name, int sortOrder) {
        CategoryRequest req = new CategoryRequest();
        req.setName(name);
        req.setSortOrder(sortOrder);
        return req;
    }

    @Nested
    @DisplayName("createCategory")
    class CreateCategory {

        @Test
        @DisplayName("管理員新增分類成功 → 會檢查同名並寫入一筆")
        void adminCreatesCategory() {
            // Arrange:沒有同名 → count 回 0
            when(categoryMapper.selectCount(any())).thenReturn(0L);
            when(categoryMapper.insert(any(Category.class))).thenReturn(1);

            // Act
            categoryService.createCategory(categoryReq("3C數位", 1), "admin");

            // Assert:驗證「行為」發生了
            verify(categoryMapper).selectCount(any());
            verify(categoryMapper).insert(any(Category.class));
        }

        @Test
        @DisplayName("同名分類已存在 → 丟 ConflictException,且不寫入")
        void duplicateNameRejected() {
            // Arrange:查同名回傳 1 → 代表已有一筆同名的「有效」分類
            when(categoryMapper.selectCount(any())).thenReturn(1L);
            // 注意:這裡「故意不」stub insert —— 因為預期它根本不該被呼叫。
            // (MockitoExtension 預設 strict,多餘的 stub 反而會被判定為錯誤)

            // Act + Assert:期望「丟出例外」而非正常回傳,所以用 assertThatThrownBy
            assertThatThrownBy(() ->
                    categoryService.createCategory(categoryReq("3C數位", 1), "admin"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("分類名稱已存在");

            // 這就是我上一輪問你的答案:失敗要「快」——
            // 撞到同名就立刻中止,絕不能再寫入。用 never() 把這個保證釘死。
            verify(categoryMapper, never()).insert(any(Category.class));
        }
    }
}
