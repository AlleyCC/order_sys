package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    List<Map<String, Object>> getAllOrdersWithStore();

    OrderDetailResponse getOrderDetail(@Param("orderId") String orderId);
}
