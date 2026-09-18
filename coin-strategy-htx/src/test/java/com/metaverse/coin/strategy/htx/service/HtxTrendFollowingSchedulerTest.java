package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendFollowingSchedulerTest {

    @Test
    void scansCandidatesOutsideCandleCloseWindowWhenNoOpenPosition() {
        HtxTrendFollowingService service = mock(HtxTrendFollowingService.class);
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxTrendFollowingScheduler scheduler = new HtxTrendFollowingScheduler(service, properties);
        HtxTrendFollowingService.RunResult expected = HtxTrendFollowingService.RunResult.of(
                HtxTrendFollowingService.RunCode.SKIPPED, null, "candidate scan complete");
        when(service.hasOpenLikePositions()).thenReturn(false);
        when(service.runOnce(false)).thenReturn(expected);

        HtxTrendFollowingService.RunResult result = scheduler.scheduledAt(Instant.ofEpochSecond(980L));

        assertEquals(HtxTrendFollowingService.RunCode.SKIPPED, result.code());
        assertTrue(result.message().contains("outside candle-close entry window"));
        verify(service).runOnce(false);
        verify(service, never()).runOnce(true);
    }

    @Test
    void allowsNewEntryScanInsideCandleCloseWindow() {
        HtxTrendFollowingService service = mock(HtxTrendFollowingService.class);
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxTrendFollowingScheduler scheduler = new HtxTrendFollowingScheduler(service, properties);
        HtxTrendFollowingService.RunResult expected = HtxTrendFollowingService.RunResult.of(
                HtxTrendFollowingService.RunCode.NO_SIGNAL, null, "checked");
        when(service.hasOpenLikePositions()).thenReturn(false);
        when(service.runOnce(true)).thenReturn(expected);

        HtxTrendFollowingService.RunResult result = scheduler.scheduledAt(Instant.ofEpochSecond(920L));

        assertEquals(expected, result);
        verify(service).runOnce(true);
    }

    @Test
    void managesExistingPositionsOutsideEntryWindowWithoutOpeningNewOnes() {
        HtxTrendFollowingService service = mock(HtxTrendFollowingService.class);
        HtxTrendProperties properties = new HtxTrendProperties();
        HtxTrendFollowingScheduler scheduler = new HtxTrendFollowingScheduler(service, properties);
        HtxTrendFollowingService.RunResult expected = HtxTrendFollowingService.RunResult.of(
                HtxTrendFollowingService.RunCode.ACTIVE, 7L, "holding");
        when(service.hasOpenLikePositions()).thenReturn(true);
        when(service.runOnce(false)).thenReturn(expected);

        HtxTrendFollowingService.RunResult result = scheduler.scheduledAt(Instant.ofEpochSecond(980L));

        assertEquals(expected, result);
        verify(service).runOnce(false);
    }
}
