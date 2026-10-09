package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.OrderItemIdempotencyKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderItemIdempotencyKeyMapper extends BaseMapper<OrderItemIdempotencyKey> {

    /**
     * 刪除建立超過 retentionHours 小時的記錄,一次最多 limit 筆,回傳刪除筆數。
     * 時間以 DB 的 NOW() 比較(created_at 也是 DB 產生的),不受應用程式時鐘影響。
     */
    int deleteExpired(@Param("retentionHours") int retentionHours, @Param("limit") int limit);
}
