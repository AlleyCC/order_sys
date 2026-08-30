package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.Resource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ResourceMapper extends BaseMapper<Resource> {

    /**
     * 查某角色被授權的資源清單(role_resources JOIN resources JOIN roles)。
     * 授權熱路徑:每次 cache miss 時呼叫。
     * 不濾 roles.status:停用角色在「查使用者角色」那段就已被排除,不會走到這裡。
     */
    @Select("""
            SELECT res.*
            FROM role_resources rr
            JOIN roles r      ON r.role_id = rr.role_id
            JOIN resources res ON res.resource_id = rr.resource_id
            WHERE r.name = #{roleName}
            """)
    List<Resource> selectResourcesByRoleName(@Param("roleName") String roleName);
}
