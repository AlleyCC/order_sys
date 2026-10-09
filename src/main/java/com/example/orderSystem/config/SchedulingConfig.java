package com.example.orderSystem.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排程的總開關,與個別排程是否啟用無關。
 *
 * 自動結算(RedisSettlementConsumer)用 app.scheduler.enabled 決定要不要存在;
 * 若總開關只掛在它身上,關掉結算會連帶停掉其他排程(例如 Idempotency-Key 的清理)。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
