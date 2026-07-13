package com.example.orderSystem.service;

import com.example.orderSystem.dto.request.CategoryRequest;
import com.example.orderSystem.dto.request.CategoryUpdateRequest;
import com.example.orderSystem.entity.Category;
import com.example.orderSystem.exception.ConflictException;
import com.example.orderSystem.exception.ResourceNotFoundException;
import com.example.orderSystem.mapper.CategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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
        @DisplayName("管理員新增分類成功 → 檢查同名後,寫入的 Category 欄位正確")
        void adminCreatesCategory() {
            // Arrange:沒有同名 → count 回 0
            when(categoryMapper.selectCount(any())).thenReturn(0L);
            when(categoryMapper.insert(any(Category.class))).thenReturn(1);

            // Act
            categoryService.createCategory(categoryReq("3C數位", 1), "admin");

            // Assert:先確認有做同名檢查
            verify(categoryMapper).selectCount(any());

            // 攔截真正被傳進 insert 的那個 Category,斷言它的欄位值。
            // 這樣一來,若 Service 漏掉 setName / setSortOrder / setCreatedBy / setIsDeleted,
            // 對應欄位會是 null,下面的斷言就會變紅並印出「expected X but was null」。
            ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
            verify(categoryMapper).insert(captor.capture());
            Category saved = captor.getValue();
            assertThat(saved.getName()).isEqualTo("3C數位");
            assertThat(saved.getSortOrder()).isEqualTo(1);
            assertThat(saved.getCreatedBy()).isEqualTo("admin");
            assertThat(saved.getIsDeleted()).isEqualTo(0);
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

    // ---- update 用的小工具:組現有 entity 與 request,免得每個測試重複 new+set ----

    private Category existing(int id, String name, int sortOrder, int version) {
        Category c = new Category();
        c.setCategoryId(id);
        c.setName(name);
        c.setSortOrder(sortOrder);
        c.setVersion(version);
        c.setIsDeleted(0);
        return c;
    }

    private CategoryUpdateRequest updateReq(Integer id, Integer version, String name, Integer sortOrder) {
        CategoryUpdateRequest r = new CategoryUpdateRequest();
        r.setCategoryId(id);
        r.setVersion(version);
        r.setName(name);          // 允許 null = 部分更新不改此欄
        r.setSortOrder(sortOrder);
        return r;
    }

    @Nested
    @DisplayName("updateCategory")
    class UpdateCategory {

        @Test
        @DisplayName("改名+排序成功 → updateById 帶對欄位,且回傳的是『重查的最新值』")
        void updatesNameAndOrder() {
            // Arrange:存在且有效的現值(version=0)
            when(categoryMapper.selectOne(any())).thenReturn(existing(1, "舊名", 1, 0));
            when(categoryMapper.selectCount(any())).thenReturn(0L);   // 沒撞到別人
            when(categoryMapper.updateById(any(Category.class))).thenReturn(1); // 樂觀鎖打中 1 列
            // Service 最後會重查一次回傳;這裡讓它回一個「新版」的物件,用來驗證回傳來源
            Category refreshed = existing(1, "新名", 5, 1);
            when(categoryMapper.selectById(any())).thenReturn(refreshed);

            // Act
            Category result = categoryService.updateCategory(updateReq(1, 0, "新名", 5), "admin");

            // Assert 1:確實有做撞名檢查
            verify(categoryMapper).selectCount(any());

            // Assert 2:攔截真正送進 updateById 的 entity,釘死每個欄位
            ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
            verify(categoryMapper).updateById(captor.capture());
            Category sent = captor.getValue();
            assertThat(sent.getCategoryId()).isEqualTo(1);
            assertThat(sent.getVersion()).isEqualTo(0);        // 帶 client 的 version 給樂觀鎖比對
            assertThat(sent.getName()).isEqualTo("新名");
            assertThat(sent.getSortOrder()).isEqualTo(5);
            assertThat(sent.getUpdatedBy()).isEqualTo("admin"); // 稽核欄

            // Assert 3:回傳的必須是「重查」的那顆(version 已 +1),不是我們手組的 update entity
            assertThat(result).isSameAs(refreshed);
        }

        @Test
        @DisplayName("只送 sortOrder(部分更新)→ 不做撞名檢查,name 不進 SQL")
        void partialUpdateSkipsNameCheck() {
            when(categoryMapper.selectOne(any())).thenReturn(existing(1, "維持不變", 1, 0));
            when(categoryMapper.updateById(any(Category.class))).thenReturn(1);
            when(categoryMapper.selectById(any())).thenReturn(existing(1, "維持不變", 9, 1));

            categoryService.updateCategory(updateReq(1, 0, null, 9), "admin");

            // name 沒送 → 根本不必查有沒有撞名
            verify(categoryMapper, never()).selectCount(any());

            // 送進 updateById 的 entity,name 應為 null(交給 MyBatis-Plus 忽略 → 不覆蓋)
            ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
            verify(categoryMapper).updateById(captor.capture());
            assertThat(captor.getValue().getName()).isNull();
            assertThat(captor.getValue().getSortOrder()).isEqualTo(9);
        }

        @Test
        @DisplayName("改成跟自己現在一樣的名字 → 也跳過撞名檢查,不誤判為重複")
        void renameToSameNameSkipsCheck() {
            when(categoryMapper.selectOne(any())).thenReturn(existing(1, "湯品", 1, 0));
            when(categoryMapper.updateById(any(Category.class))).thenReturn(1);
            when(categoryMapper.selectById(any())).thenReturn(existing(1, "湯品", 2, 1));

            categoryService.updateCategory(updateReq(1, 0, "湯品", 2), "admin");

            // 新名字 == 現值 → 不是改名,不必查撞名
            verify(categoryMapper, never()).selectCount(any());
            verify(categoryMapper).updateById(any(Category.class));
        }

        @Test
        @DisplayName("改後名稱撞到別的有效分類 → 丟 Conflict,且絕不 updateById")
        void duplicateNameRejected() {
            when(categoryMapper.selectOne(any())).thenReturn(existing(1, "舊名", 1, 0));
            when(categoryMapper.selectCount(any())).thenReturn(1L);   // 別人已用這名字

            assertThatThrownBy(() ->
                    categoryService.updateCategory(updateReq(1, 0, "撞名", 2), "admin"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("分類名稱已存在");

            verify(categoryMapper, never()).updateById(any(Category.class));
        }

        @Test
        @DisplayName("分類不存在或已軟刪 → 丟 NotFound,不進 update")
        void notFoundRejected() {
            when(categoryMapper.selectOne(any())).thenReturn(null); // 查不到有效分類

            assertThatThrownBy(() ->
                    categoryService.updateCategory(updateReq(999, 0, "隨便", 1), "admin"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("分類不存在");

            verify(categoryMapper, never()).updateById(any(Category.class));
        }

        @Test
        @DisplayName("version 對不上(有人搶先改)→ updateById 回 0 → 丟 Conflict")
        void staleVersionRejected() {
            when(categoryMapper.selectOne(any())).thenReturn(existing(1, "炸物", 1, 0));
            when(categoryMapper.updateById(any(Category.class))).thenReturn(0); // 樂觀鎖打中 0 列

            assertThatThrownBy(() ->
                    categoryService.updateCategory(updateReq(1, 0, null, 7), "admin"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("分類已被其他人修改,請重新載入");

            // 衝突後不該再重查(存在性已知,直接中止)
            verify(categoryMapper, never()).selectById(any());
        }
    }
}
