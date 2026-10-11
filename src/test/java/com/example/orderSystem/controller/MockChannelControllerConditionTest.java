package com.example.orderSystem.controller;

import com.example.orderSystem.payment.MockPayChannel;
import com.example.orderSystem.service.RechargeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MockChannelControllerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MockPayChannel.class, () -> mock(MockPayChannel.class))
            .withBean(RechargeService.class, () -> mock(RechargeService.class))
            .withUserConfiguration(MockChannelController.class);

    @Test
    @DisplayName("沒有設定 app.pay.channel 時,不註冊模擬付款端點")
    void absentByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(MockChannelController.class));
    }

    @Test
    @DisplayName("app.pay.channel=mock 時才註冊模擬付款端點")
    void presentWhenMockChannelSelected() {
        runner.withPropertyValues("app.pay.channel=mock")
                .run(context -> assertThat(context).hasSingleBean(MockChannelController.class));
    }
}
