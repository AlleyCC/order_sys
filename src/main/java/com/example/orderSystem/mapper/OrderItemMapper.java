package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    long getFrozenAmount(@Param("userId") String userId);
}
