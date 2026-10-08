package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineCandleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendRuntimeKlineCandleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendRuntimeKlineSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendRuntimeKlineRecorderTest {

    @Test void stores_content_addressed_bars_and_a_reconstructible_window() {
        HtxTrendRuntimeKlineCandleMapper candleMapper = mock(HtxTrendRuntimeKlineCandleMapper.class);
        HtxTrendRuntimeKlineSnapshotMapper snapshotMapper = mock(HtxTrendRuntimeKlineSnapshotMapper.class);
        HtxTrendRuntimeKlineRecorder recorder = new HtxTrendRuntimeKlineRecorder(candleMapper, snapshotMapper);

        recorder.capture(request(List.of(candle(1_790_000_000L, "100"), candle(1_790_000_900L, "101"))));

        ArgumentCaptor<List<HtxTrendRuntimeKlineCandleEntity>> candles = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<HtxTrendRuntimeKlineSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendRuntimeKlineSnapshotEntity.class);
        verify(candleMapper).insertIgnoreBatch(candles.capture());
        verify(snapshotMapper).insert(snapshot.capture());
        assertEquals(2, candles.getValue().size());
        assertNotEquals(candles.getValue().get(0).getCandleHash(), candles.getValue().get(1).getCandleHash());
        assertEquals("ENTRY_GATE", snapshot.getValue().getCaptureStage());
        assertEquals("run-42", snapshot.getValue().getRunId());
        assertEquals(42L, snapshot.getValue().getFactorSnapshotId());
        assertEquals(9L, snapshot.getValue().getPositionId());
        assertEquals(1_790_000_000L, snapshot.getValue().getWindowStartBarTime());
        assertEquals(1_790_000_900L, snapshot.getValue().getWindowEndBarTime());
        assertFalse(snapshot.getValue().getCompleteWindow());
        assertEquals("PARTIAL", snapshot.getValue().getDataIntegrity());
        assertTrue(snapshot.getValue().getWindowSha256().matches("[0-9a-f]{64}"));
    }

    @Test void skips_an_already_recorded_identical_window_but_keeps_a_revised_bar_version() {
        HtxTrendRuntimeKlineCandleMapper candleMapper = mock(HtxTrendRuntimeKlineCandleMapper.class);
        HtxTrendRuntimeKlineSnapshotMapper snapshotMapper = mock(HtxTrendRuntimeKlineSnapshotMapper.class);
        HtxTrendRuntimeKlineRecorder recorder = new HtxTrendRuntimeKlineRecorder(candleMapper, snapshotMapper);
        when(snapshotMapper.findBySnapshotKey(anyString())).thenReturn(new HtxTrendRuntimeKlineSnapshotEntity());
        recorder.capture(request(List.of(candle(1_790_000_000L, "100"), candle(1_790_000_900L, "101"))));
        verify(candleMapper, never()).insertIgnoreBatch(any());
        verify(snapshotMapper, never()).insert(any());

        HtxTrendRuntimeKlineSnapshotMapper revisedSnapshotMapper = mock(HtxTrendRuntimeKlineSnapshotMapper.class);
        HtxTrendRuntimeKlineRecorder revisedRecorder = new HtxTrendRuntimeKlineRecorder(candleMapper, revisedSnapshotMapper);
        revisedRecorder.capture(request(List.of(candle(1_790_000_000L, "100"), candle(1_790_000_900L, "102"))));
        ArgumentCaptor<HtxTrendRuntimeKlineSnapshotEntity> revised = ArgumentCaptor.forClass(HtxTrendRuntimeKlineSnapshotEntity.class);
        verify(revisedSnapshotMapper).insert(revised.capture());
        assertTrue(revised.getValue().getSnapshotKey().contains("ENTRY_GATE"));
    }

    @Test void clamps_the_decision_bar_to_the_latest_captured_completed_bar() {
        HtxTrendRuntimeKlineCandleMapper candleMapper = mock(HtxTrendRuntimeKlineCandleMapper.class);
        HtxTrendRuntimeKlineSnapshotMapper snapshotMapper = mock(HtxTrendRuntimeKlineSnapshotMapper.class);
        HtxTrendRuntimeKlineRecorder recorder = new HtxTrendRuntimeKlineRecorder(candleMapper, snapshotMapper);
        HtxTrendRuntimeKlineRecorder.CaptureRequest request = new HtxTrendRuntimeKlineRecorder.CaptureRequest(
                "POSITION_MANAGEMENT", "BTC-USDT", "60min", 1_790_007_200L, 2, "run-42", 42L, 9L,
                List.of(candle(1_790_000_000L, "100"), candle(1_790_003_600L, "101")));

        recorder.capture(request);

        ArgumentCaptor<HtxTrendRuntimeKlineSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendRuntimeKlineSnapshotEntity.class);
        verify(snapshotMapper).insert(snapshot.capture());
        assertEquals(1_790_003_600L, snapshot.getValue().getTargetBarTime());
        assertEquals(1_790_003_600L, snapshot.getValue().getWindowEndBarTime());
    }

    @Test void retries_a_deadlocked_candle_batch_without_losing_the_window_snapshot() {
        HtxTrendRuntimeKlineCandleMapper candleMapper = mock(HtxTrendRuntimeKlineCandleMapper.class);
        HtxTrendRuntimeKlineSnapshotMapper snapshotMapper = mock(HtxTrendRuntimeKlineSnapshotMapper.class);
        when(candleMapper.insertIgnoreBatch(any()))
                .thenThrow(new RuntimeException("Deadlock found when trying to get lock; try restarting transaction"))
                .thenReturn(1);

        new HtxTrendRuntimeKlineRecorder(candleMapper, snapshotMapper)
                .capture(request(List.of(candle(1_790_000_000L, "100"))));

        verify(candleMapper, times(2)).insertIgnoreBatch(any());
        verify(snapshotMapper).insert(any(HtxTrendRuntimeKlineSnapshotEntity.class));
    }

    @Test void marks_a_same_size_window_with_a_missing_bar_as_partial() {
        HtxTrendRuntimeKlineCandleMapper candleMapper = mock(HtxTrendRuntimeKlineCandleMapper.class);
        HtxTrendRuntimeKlineSnapshotMapper snapshotMapper = mock(HtxTrendRuntimeKlineSnapshotMapper.class);
        HtxTrendRuntimeKlineRecorder recorder = new HtxTrendRuntimeKlineRecorder(candleMapper, snapshotMapper);
        HtxTrendRuntimeKlineRecorder.CaptureRequest request = new HtxTrendRuntimeKlineRecorder.CaptureRequest(
                "ENTRY_GATE", "BTC-USDT", "15min", 1_790_001_800L, 3, "run-42", 42L, 9L,
                List.of(candle(1_790_000_000L, "100"), candle(1_790_000_900L, "101"),
                        candle(1_790_002_700L, "102")));

        recorder.capture(request);

        ArgumentCaptor<HtxTrendRuntimeKlineSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendRuntimeKlineSnapshotEntity.class);
        verify(snapshotMapper).insert(snapshot.capture());
        assertFalse(snapshot.getValue().getCompleteWindow());
        assertEquals("PARTIAL", snapshot.getValue().getDataIntegrity());
    }

    @Test void marks_a_continuous_window_complete() {
        HtxTrendRuntimeKlineCandleMapper candleMapper = mock(HtxTrendRuntimeKlineCandleMapper.class);
        HtxTrendRuntimeKlineSnapshotMapper snapshotMapper = mock(HtxTrendRuntimeKlineSnapshotMapper.class);
        HtxTrendRuntimeKlineRecorder recorder = new HtxTrendRuntimeKlineRecorder(candleMapper, snapshotMapper);
        HtxTrendRuntimeKlineRecorder.CaptureRequest request = new HtxTrendRuntimeKlineRecorder.CaptureRequest(
                "ENTRY_GATE", "BTC-USDT", "15min", 1_790_000_900L, 2, "run-42", 42L, 9L,
                List.of(candle(1_790_000_000L, "100"), candle(1_790_000_900L, "101")));

        recorder.capture(request);

        ArgumentCaptor<HtxTrendRuntimeKlineSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendRuntimeKlineSnapshotEntity.class);
        verify(snapshotMapper).insert(snapshot.capture());
        assertTrue(snapshot.getValue().getCompleteWindow());
        assertEquals("COMPLETE", snapshot.getValue().getDataIntegrity());
    }

    private static HtxTrendRuntimeKlineRecorder.CaptureRequest request(List<HtxSwapKline> candles) {
        return new HtxTrendRuntimeKlineRecorder.CaptureRequest("ENTRY_GATE", "BTC-USDT", "15min", 1_790_000_900L,
                160, "run-42", 42L, 9L, candles);
    }

    private static HtxSwapKline candle(long time, String close) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(time);
        candle.setOpen(new BigDecimal(close));
        candle.setHigh(new BigDecimal(close).add(BigDecimal.ONE));
        candle.setLow(new BigDecimal(close).subtract(BigDecimal.ONE));
        candle.setClose(new BigDecimal(close));
        candle.setAmount(new BigDecimal("10"));
        candle.setVol(new BigDecimal("1000"));
        candle.setTradeTurnover(new BigDecimal("1000"));
        candle.setCount(10L);
        return candle;
    }
}
