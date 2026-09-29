package com.metaverse.coin.strategy.htx.research;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendMarketRegimeSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendMarketRegimeSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HtxTrendMarketRegimeServiceTest {

    @Mock HtxTrendMarketRegimeSnapshotMapper snapshotMapper;

    @Test
    void classifies_aligned_trend_only_when_breadth_supports_the_direction() {
        HtxTrendMarketRegimeService service = new HtxTrendMarketRegimeService(snapshotMapper);

        assertEquals("TREND_UP", service.classify(input("LONG", "LONG", "LONG", new BigDecimal("0.70"), null, null, "COMPLETE")));
        assertEquals("RANGE", service.classify(input("LONG", "LONG", "LONG", new BigDecimal("0.52"), null, null, "COMPLETE")));
        assertEquals("TREND_DOWN", service.classify(input("SHORT", "SHORT", "SHORT", new BigDecimal("0.30"), null, null, "COMPLETE")));
    }

    @Test
    void gives_disorder_and_crowding_priority_before_a_trend_label() {
        HtxTrendMarketRegimeService service = new HtxTrendMarketRegimeService(snapshotMapper);

        assertEquals("VOLATILE_DISORDER", service.classify(input("LONG", "LONG", "LONG", new BigDecimal("0.70"), new BigDecimal("0.80"), null, "COMPLETE")));
        assertEquals("CROWDED", service.classify(input("LONG", "LONG", "LONG", new BigDecimal("0.70"), null, new BigDecimal("0.90"), "COMPLETE")));
    }

    @Test
    void partial_inputs_are_recorded_as_unclassified_not_as_a_false_trend() {
        HtxTrendMarketRegimeService service = new HtxTrendMarketRegimeService(snapshotMapper);

        assertEquals("UNCLASSIFIED", service.classify(input("LONG", "LONG", "LONG", new BigDecimal("0.70"), null, null, "PARTIAL")));
    }

    @Test
    void records_once_and_reuses_the_same_immutable_snapshot_key() {
        HtxTrendMarketRegimeService service = new HtxTrendMarketRegimeService(snapshotMapper);
        HtxTrendMarketRegimeService.MarketRegimeInput input = input("LONG", "LONG", "LONG", new BigDecimal("0.70"), null, null, "COMPLETE");

        HtxTrendMarketRegimeSnapshotEntity written = service.classifyAndRecord(input);

        assertEquals("TREND_UP", written.getStateCode());
        ArgumentCaptor<HtxTrendMarketRegimeSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendMarketRegimeSnapshotEntity.class);
        verify(snapshotMapper).insert(snapshot.capture());
        assertEquals("TREND_UP", snapshot.getValue().getStateCode());

        HtxTrendMarketRegimeSnapshotEntity existing = new HtxTrendMarketRegimeSnapshotEntity();
        existing.setStateCode("RANGE");
        when(snapshotMapper.findBySnapshotKey(anyString())).thenReturn(existing);
        assertEquals(existing, service.classifyAndRecord(input));
        verify(snapshotMapper, times(1)).insert(any());
    }

    private static HtxTrendMarketRegimeService.MarketRegimeInput input(String daily, String fourHour, String oneHour,
                                                                         BigDecimal breadth, BigDecimal volatility,
                                                                         BigDecimal crowding, String integrity) {
        return new HtxTrendMarketRegimeService.MarketRegimeInput("state:test", "research:test", "MARKET", null, 1L,
                daily, fourHour, oneHour, breadth, volatility, crowding, "{\"source\":\"test\"}", integrity);
    }
}
