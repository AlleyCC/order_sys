package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 受動態授權管制的後端 API 資源(URL pattern + HTTP method) */
@Data
@TableName("resources")
public class Resource {

    @TableId(type = IdType.AUTO)
    private Long resourceId;

    /** Spring PathPattern 語法,如 /category/create_category、/campaign/** */
    private String urlPattern;

    /** GET/POST/PUT/PATCH/DELETE/ALL */
    private String httpMethod;

    private String name;

    /** 分組(管理介面呈現用) */
    private String category;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
