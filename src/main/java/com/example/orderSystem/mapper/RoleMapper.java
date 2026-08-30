package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 查某使用者擁有的「啟用中」角色名清單(user_roles JOIN roles)。
     * 授權熱路徑:每次 cache miss 時呼叫。
     */
    @Select("""
            SELECT r.name
            FROM user_roles ur
            JOIN roles r ON r.role_id = ur.role_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
            """)
    List<String> selectRoleNamesByUserId(@Param("userId") String userId);
}
