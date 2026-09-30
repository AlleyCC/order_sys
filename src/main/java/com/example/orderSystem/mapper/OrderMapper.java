package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.orderSystem.dto.response.OrderDetailResponse;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.enums.OrderStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    IPage<Map<String, Object>> getOpenOrdersWithStore(IPage<Map<String, Object>> page);

    IPage<Map<String, Object>> getAllOrdersWithStore(IPage<Map<String, Object>> page);

    OrderDetailResponse getOrderDetail(@Param("orderId") String orderId);

    /**
     * 條件式改狀態:只有 DB 當下的狀態在 from 清單內才寫入 to。
     * 回傳 0 代表狀態已被別的請求改掉 —— 呼叫端不能再用自己先前讀到的舊狀態做決定。
     */
    int updateStatusIfIn(@Param("orderId") String orderId,
                         @Param("to") OrderStatus to,
                         @Param("from") Collection<OrderStatus> from);
}
