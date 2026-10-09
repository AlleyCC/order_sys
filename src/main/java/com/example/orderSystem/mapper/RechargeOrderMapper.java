package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.enums.RechargeStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;

@Mapper
public interface RechargeOrderMapper extends BaseMapper<RechargeOrder> {

    /**
     * 條件式入帳認領:只有 DB 當下的狀態在 from 清單內才改成 SUCCESS。
     * 回傳 0 代表已經入帳過,或狀態不允許 —— 呼叫端不能繼續加餘額。
     */
    int markPaidIfIn(@Param("rechargeId") String rechargeId,
                     @Param("paidAt") LocalDateTime paidAt,
                     @Param("from") Collection<RechargeStatus> from);

    /** 條件式關單:只有仍在 WAITING 才改成 CLOSED。回傳 0 代表已被入帳或已關閉。 */
    int closeIfWaiting(@Param("rechargeId") String rechargeId);
}
