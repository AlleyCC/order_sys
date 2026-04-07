package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    IPage<Map<String, Object>> getAllOrdersWithStore(IPage<Map<String, Object>> page);

    OrderDetailResponse getOrderDetail(@Param("orderId") String orderId);
}
