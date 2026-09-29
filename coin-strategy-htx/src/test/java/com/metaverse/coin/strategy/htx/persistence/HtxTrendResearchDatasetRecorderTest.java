package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchCandleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchDatasetEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchCandleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchDatasetMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HtxTrendResearchDatasetRecorderTest {

    @Mock HtxTrendResearchDatasetMapper datasetMapper;
    @Mock HtxTrendResearchCandleMapper candleMapper;

    @Test
    void freezes_a_valid_partial_dataset_and_records_its_immutable_terminal_state() {
        HtxTrendResearchDatasetRecorder recorder = new HtxTrendResearchDatasetRecorder(datasetMapper, candleMapper);
        HtxTrendResearchDatasetFreezeRequest request = request("research:test-partial", "PARTIAL", List.of(candle("research:test-partial", 1L)));
        when(candleMapper.insertBatch(anyList())).thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size());

        HtxTrendResearchDatasetRecorder.FreezeResult result = recorder.freeze(request);

        assertEquals("FROZEN", result.status());
        assertEquals(1, result.storedCandles());
        assertEquals(false, result.alreadyFrozen());
        ArgumentCaptor<HtxTrendResearchDatasetEntity> dataset = ArgumentCaptor.forClass(HtxTrendResearchDatasetEntity.class);
        verify(datasetMapper).insert(dataset.capture());
        assertEquals("FREEZING", dataset.getValue().getStatus());
        verify(datasetMapper).finishFreeze(eq("research:test-partial"), eq("FROZEN"), anyString());
    }

    @Test
    void returns_an_identical_frozen_dataset_without_reinserting_candles() {
        HtxTrendResearchDatasetRecorder recorder = new HtxTrendResearchDatasetRecorder(datasetMapper, candleMapper);
        HtxTrendResearchDatasetFreezeRequest request = request("research:already-frozen", "PARTIAL", List.of(candle("research:already-frozen", 1L)));
        HtxTrendResearchDatasetEntity existing = new HtxTrendResearchDatasetEntity();
        existing.setStatus("FROZEN");
        existing.setInputChecksum("checksum-test");
        when(datasetMapper.findByDatasetKey(request.datasetKey())).thenReturn(existing);

        HtxTrendResearchDatasetRecorder.FreezeResult result = recorder.freeze(request);

        assertEquals(true, result.alreadyFrozen());
        assertEquals(0, result.storedCandles());
        verify(datasetMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(candleMapper, never()).insertBatch(anyList());
    }

    @Test
    void failed_batch_leaves_failed_state_and_never_reports_a_frozen_dataset() {
        HtxTrendResearchDatasetRecorder recorder = new HtxTrendResearchDatasetRecorder(datasetMapper, candleMapper);
        HtxTrendResearchDatasetFreezeRequest request = request("research:failed", "PARTIAL", List.of(candle("research:failed", 1L)));
        when(candleMapper.insertBatch(anyList())).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(HtxTrendResearchDatasetRecorder.ResearchDatasetFreezeException.class, () -> recorder.freeze(request));

        verify(datasetMapper).finishFreeze(eq("research:failed"), eq("FAILED"), anyString());
        verify(datasetMapper, never()).finishFreeze(eq("research:failed"), eq("FROZEN"), anyString());
    }

    @Test
    void complete_input_rejects_a_missing_bar_instead_of_claiming_full_coverage() {
        HtxTrendResearchDatasetRecorder recorder = new HtxTrendResearchDatasetRecorder(datasetMapper, candleMapper);
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
        HtxTrendResearchDatasetFreezeRequest request = new HtxTrendResearchDatasetFreezeRequest(
                "research:gap", "FROZEN_PUBLIC_KLINE", "local", start, start, start.plusMinutes(30),
                "[\"BTC-USDT\"]", "[\"15min\"]", "checksum-gap", "COMPLETE", "RESEARCH", "{}",
                List.of(candle("research:gap", start.atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond())));

        assertThrows(IllegalArgumentException.class, () -> recorder.freeze(request));
        verify(datasetMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    private static HtxTrendResearchDatasetFreezeRequest request(String key, String integrity, List<HtxTrendResearchCandleEntity> candles) {
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
        return new HtxTrendResearchDatasetFreezeRequest(key, "FROZEN_PUBLIC_KLINE", "local", start, start, start.plusMinutes(15),
                "[\"BTC-USDT\"]", "[\"15min\"]", "checksum-test", integrity, "RESEARCH", "{\"collector\":\"test\"}", candles);
    }

    private static HtxTrendResearchCandleEntity candle(String datasetKey, long barTime) {
        HtxTrendResearchCandleEntity candle = new HtxTrendResearchCandleEntity();
        candle.setDatasetKey(datasetKey);
        candle.setContractCode("BTC-USDT");
        candle.setPeriod("15min");
        candle.setBarTime(barTime);
        candle.setOpenPrice(new BigDecimal("100"));
        candle.setHighPrice(new BigDecimal("102"));
        candle.setLowPrice(new BigDecimal("99"));
        candle.setClosePrice(new BigDecimal("101"));
        candle.setSourceKind("RAW_PUBLIC");
        return candle;
    }
}
