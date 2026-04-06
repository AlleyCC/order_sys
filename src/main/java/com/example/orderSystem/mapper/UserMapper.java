package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    int casDebit(@Param("userId") String userId, @Param("amount") long amount);
}
