package com.example.orderSystem.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 整合測試共用的可撥動時鐘;放在基底類別,所有整合測試仍共用同一個 Spring context。 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
