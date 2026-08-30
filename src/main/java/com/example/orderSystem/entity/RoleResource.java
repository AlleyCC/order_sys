package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("role_resources")
public class RoleResource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roleId;
    private Long resourceId;
    private LocalDateTime createdAt;
}
