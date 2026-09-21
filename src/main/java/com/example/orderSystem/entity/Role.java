package com.example.orderSystem.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("roles")
public class Role {

    @TableId(type = IdType.AUTO)
    private Long roleId;

    /** 角色代碼(SUPER_ADMIN/ADMIN_STAFF/CUSTOMER_SERVICE/ACCOUNTANT),也是 Redis 快取 key 的成分 */
    private String name;

    private String description;

    /** 1=啟用, 0=停用;停用角色不參與授權 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
