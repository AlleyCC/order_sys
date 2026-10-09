package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.orderSystem.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    IPage<Notification> selectUnread(IPage<Notification> page, @Param("userId") String userId);

    /**
     * 條件式標為已讀:只更新屬於 userId 且尚未讀過的那一則。
     * 回傳 0 代表不存在、不是自己的、或已經讀過 —— 呼叫端要自己區分。
     */
    int markRead(@Param("notificationId") String notificationId, @Param("userId") String userId);
}
