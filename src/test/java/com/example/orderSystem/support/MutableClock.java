package com.example.orderSystem.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 以系統時間為基準、可往前撥的時鐘。測試「過期」「時間窗」時呼叫 advance,
 * 不必真的等待;測試結束呼叫 reset 回到系統時間。
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Duration offset = Duration.ZERO;

    public MutableClock() {
        this(ZoneId.systemDefault());
    }

    private MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock clock = new MutableClock(zone);
        clock.offset = offset;
        return clock;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
