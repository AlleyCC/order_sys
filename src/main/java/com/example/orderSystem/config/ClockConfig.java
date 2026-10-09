package com.example.orderSystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 時間來源。需要判斷「過期」「時間窗」的程式注入這個 Clock,
 * 測試換成可前進的 Clock,就不必真的等上十幾分鐘。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
