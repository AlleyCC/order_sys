package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    int casDebit(@Param("userId") String userId, @Param("amount") long amount);

    /**
     * 以行鎖讀取使用者。凍結金額是 order_items 上的 SUM,沒有實體可鎖,
     * 因此改鎖 users 這一行 —— 讓同一使用者的所有下單在此排隊。
     * 必須在交易內呼叫,鎖到 commit 才釋放。
     */
    User selectForUpdate(@Param("userId") String userId);
}
