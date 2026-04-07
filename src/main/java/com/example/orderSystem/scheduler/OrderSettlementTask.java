package com.example.orderSystem.scheduler;

import lombok.Getter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

@Getter
public class OrderSettlementTask implements Delayed {

    private final String orderId;
    private final long deadlineMillis;

    public OrderSettlementTask(String orderId, LocalDateTime deadline) {
        this.orderId = orderId;
        this.deadlineMillis = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    public long getDelay(TimeUnit unit) {
        long diff = deadlineMillis - System.currentTimeMillis();
        return unit.convert(diff, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        return Long.compare(this.getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return orderId.equals(((OrderSettlementTask) o).orderId);
    }

    @Override
    public int hashCode() {
        return orderId.hashCode();
    }
}
