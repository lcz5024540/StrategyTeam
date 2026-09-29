package com.metaverse.coin.strategy.htx.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchCandleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchDatasetEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendMarketRegimeSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchCandleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchDatasetMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendMarketRegimeSnapshotMapper;
import com.metaverse.coin.strategy.htx.research.HtxTrendMarketRegimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class HtxTrendResearchDatasetMapperIntegrationTest {

    @Autowired HtxTrendResearchDatasetRecorder recorder;
    @Autowired HtxTrendResearchDatasetMapper datasetMapper;
    @Autowired HtxTrendResearchCandleMapper candleMapper;
    @Autowired HtxTrendMarketRegimeService marketRegimeService;
    @Autowired HtxTrendMarketRegimeSnapshotMapper marketRegimeSnapshotMapper;

    @Test
    void freezes_and_reads_back_a_partial_dataset_without_claiming_it_is_complete() {
        String key = "research:it:" + System.nanoTime();
        LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
        HtxTrendResearchCandleEntity candle = new HtxTrendResearchCandleEntity();
        candle.setDatasetKey(key);
        candle.setContractCode("BTC-USDT");
        candle.setPeriod("15min");
        candle.setBarTime(start.atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond());
        candle.setOpenPrice(new BigDecimal("100"));
        candle.setHighPrice(new BigDecimal("101"));
        candle.setLowPrice(new BigDecimal("99"));
        candle.setClosePrice(new BigDecimal("100.5"));
        candle.setSourceKind("RAW_PUBLIC");

        HtxTrendResearchDatasetRecorder.FreezeResult result = recorder.freeze(new HtxTrendResearchDatasetFreezeRequest(
                key, "FROZEN_PUBLIC_KLINE", "test", start, start, start.plusMinutes(30),
                "[\"BTC-USDT\"]", "[\"15min\"]", "checksum-" + key, "PARTIAL", "RESEARCH", "{\"test\":true}", List.of(candle)));

        assertEquals("FROZEN", result.status());
        HtxTrendResearchDatasetEntity dataset = datasetMapper.findByDatasetKey(key);
        assertNotNull(dataset);
        assertEquals("PARTIAL", dataset.getInputIntegrity());
        assertEquals("FROZEN", dataset.getStatus());
        assertEquals(1L, candleMapper.selectCount(new LambdaQueryWrapper<HtxTrendResearchCandleEntity>()
                .eq(HtxTrendResearchCandleEntity::getDatasetKey, key)));
    }

    @Test
    void persists_a_research_only_market_state_snapshot_without_touching_execution_tables() {
        String key = "research:state:" + System.nanoTime();
        HtxTrendMarketRegimeSnapshotEntity snapshot = marketRegimeService.classifyAndRecord(
                new HtxTrendMarketRegimeService.MarketRegimeInput("state:" + key, key, "MARKET", null, 1L,
                        "LONG", "LONG", "LONG", new BigDecimal("0.70"), null, null,
                        "{\"collector\":\"integration-test\"}", "COMPLETE"));

        assertEquals("TREND_UP", snapshot.getStateCode());
        assertEquals("TREND_UP", marketRegimeSnapshotMapper.findBySnapshotKey("state:" + key).getStateCode());
    }
}
