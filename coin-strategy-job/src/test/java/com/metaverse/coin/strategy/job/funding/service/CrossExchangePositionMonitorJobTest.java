package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePaperTradingService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangePositionMonitorService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossExchangePositionMonitorJobTest {

    @Test
    void publishesPositionAndStrategyStateAfterMonitoring() {
        CrossExchangePositionMonitorService monitor = mock(CrossExchangePositionMonitorService.class);
        CrossExchangePaperTradingService paper = mock(CrossExchangePaperTradingService.class);
        CrossExchangeMetrics metrics = mock(CrossExchangeMetrics.class);
        when(paper.positions()).thenReturn(List.of());
        when(paper.isCircuitOpen()).thenReturn(true);
        when(paper.isPaused()).thenReturn(false);

        new CrossExchangePositionMonitorJob(monitor, paper, metrics).monitor();

        verify(monitor).monitor(any(Instant.class));
        verify(metrics).positions(List.of());
        verify(metrics).strategyState(true, false);
    }
}
