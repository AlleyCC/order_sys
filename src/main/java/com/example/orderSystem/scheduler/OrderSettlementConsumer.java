package com.example.orderSystem.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.service.OrderService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OrderSettlementConsumer {

    private final OrderSettlementQueue settlementQueue;
    private final OrderService orderService;
    private final OrderMapper orderMapper;

    private Thread consumerThread;

    @PostConstruct
    public void init() {
        recoverFromDb();
        startConsumer();
    }

    @PreDestroy
    public void destroy() {
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }

    private void recoverFromDb() {
        List<Order> openOrders = orderMapper.selectList(
                new LambdaQueryWrapper<Order>().eq(Order::getStatus, OrderStatus.OPEN)
        );
        for (Order order : openOrders) {
            settlementQueue.add(order.getOrderId(), order.getDeadline());
        }
        log.info("Recovered {} OPEN orders into settlement queue", openOrders.size());
    }

    private void startConsumer() {
        consumerThread = new Thread(() -> {
            log.info("Settlement consumer started");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    OrderSettlementTask task = settlementQueue.take();
                    log.info("Deadline reached for order {}, starting settlement", task.getOrderId());
                    orderService.settleOrder(task.getOrderId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Settlement consumer interrupted, shutting down");
                } catch (Exception e) {
                    log.error("Error during settlement", e);
                }
            }
        }, "settlement-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
    }
}
