package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("categories")
public class Category {

    @TableId(type = IdType.AUTO)
    private Integer categoryId;
    private String name;
    private Integer sortOrder;
    private Integer isDeleted;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;

    // 注意:資料庫的 active_flag 是 generated column(由 is_deleted 衍生),
    // 應用程式不該寫它,所以這裡刻意「不映射」這個欄位。
}
