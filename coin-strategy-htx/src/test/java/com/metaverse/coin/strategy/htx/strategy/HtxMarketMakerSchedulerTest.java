package com.metaverse.coin.strategy.htx.strategy;

import com.metaverse.coin.strategy.common.client.htx.HtxApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class HtxMarketMakerSchedulerTest {

    MarketMakerService svc;
    HtxMarketMakerScheduler scheduler;

    @BeforeEach void setup() {
        svc = mock(MarketMakerService.class);
        scheduler = new HtxMarketMakerScheduler(svc);
    }

    @Test void scheduled_delegates_to_runOnce() {
        scheduler.scheduled();
        verify(svc).runOnce();
    }

    @Test void onReady_delegates_to_recoverOpenCells() {
        scheduler.onReady();
        verify(svc).recoverOpenCells();
    }

    @Test void scheduled_swallows_runtime_exception() {
        doThrow(new RuntimeException("boom")).when(svc).runOnce();
        scheduler.scheduled(); // 不应抛出——调度线程不被拖垮
        verify(svc).runOnce();
    }

    @Test void onReady_swallows_runtime_exception() {
        doThrow(new RuntimeException("boom")).when(svc).recoverOpenCells();
        scheduler.onReady(); // 不应抛出——启动流程不被拖垮
        verify(svc).recoverOpenCells();
    }

    @Test void schedulerRequiresBothTradeAndMarketMakerSwitches() {
        ConditionalOnProperty condition = HtxMarketMakerScheduler.class.getAnnotation(ConditionalOnProperty.class);

        assertEquals("htx", condition.prefix());
        assertEquals(Set.of("trade.enabled", "market-maker.enabled"), Set.of(condition.name()));
        assertEquals("true", condition.havingValue());
    }

    @Test void schedulerLoadsOnlyWhenBothSwitchesAreEnabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(HtxApiService.class, () -> mock(HtxApiService.class))
                .withBean(MarketMakerService.class, () -> mock(MarketMakerService.class))
                .withUserConfiguration(HtxMarketMakerScheduler.class);

        runner.withPropertyValues("htx.trade.enabled=false", "htx.market-maker.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(HtxMarketMakerScheduler.class));
        runner.withPropertyValues("htx.trade.enabled=true", "htx.market-maker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HtxMarketMakerScheduler.class));
        runner.withPropertyValues("htx.trade.enabled=true", "htx.market-maker.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(HtxMarketMakerScheduler.class));
    }
}
