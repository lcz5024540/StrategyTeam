package com.metaverse.coin.strategy.htx.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
