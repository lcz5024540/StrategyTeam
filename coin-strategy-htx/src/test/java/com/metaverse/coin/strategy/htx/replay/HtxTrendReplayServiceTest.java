package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendCandidateSignalMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendReplayTradeEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendReplayCandleEntity;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendReplayServiceTest {

    private static final List<String> EXIT_PERIODS = List.of("30min", "60min", "4hour");

    @Test
    void countsConfirmedOpposingHigherTimeframesForLongPosition() {
        long entryBarTime = 101_000L;
        Map<Long, Integer> opposition = HtxTrendReplayService.computeOppositionByEntryBar(
                Map.of(
                        "30min", candles("30min", List.of("100", "99", "98", "97", "96"),
                                List.of(10_000L, 20_000L, 30_000L, 40_000L, 50_000L)),
                        "60min", candles("60min", List.of("100", "99", "98", "97", "96"),
                                List.of(10_000L, 20_000L, 30_000L, 40_000L, 50_000L)),
                        "4hour", candles("4hour", List.of("96", "97", "98", "99", "100"),
                                List.of(10_000L, 30_000L, 50_000L, 70_000L, 85_000L))),
                "LONG", List.of(candle("15min", entryBarTime, "100")), EXIT_PERIODS, 2, 3);

        assertEquals(2, opposition.get(entryBarTime));
    }

    @Test
    void omitsBarWhenAnyRequiredHigherTimeframeIsUnavailable() {
        long entryBarTime = 101_000L;
        Map<Long, Integer> opposition = HtxTrendReplayService.computeOppositionByEntryBar(
                Map.of(
                        "30min", candles("30min", List.of("100", "99", "98", "97", "96"),
                                List.of(10_000L, 20_000L, 30_000L, 40_000L, 50_000L)),
                        "60min", candles("60min", List.of("100", "99", "98", "97", "96"),
                                List.of(10_000L, 20_000L, 30_000L, 40_000L, 50_000L))),
                "LONG", List.of(candle("15min", entryBarTime, "100")), EXIT_PERIODS, 2, 3);

        assertFalse(opposition.containsKey(entryBarTime));
    }

    @Test
    void replaySelectionUsesOnlyDurableExecutedEntries() throws NoSuchMethodException {
        Select select = HtxTrendCandidateSignalMapper.class
                .getMethod("findReplayExecutedCandidatesBetween", long.class, long.class)
                .getAnnotation(Select.class);

        assertNotNull(select);
        String statement = select.value()[0];
        assertTrue(statement.contains("INNER JOIN htx_trend_position"));
        assertTrue(statement.contains("bar_time >= #{startBarTime}"));
        assertTrue(statement.contains("bar_time <= #{endBarTime}"));
        assertTrue(statement.contains("ORDER_SUBMITTED"));
    }

    @Test
    void treatsSubmittedExchangeOrdersAsExecutedGateEvidence() {
        assertTrue(HtxTrendReplayService.hasExecutedGate("PASSED"));
        assertTrue(HtxTrendReplayService.hasExecutedGate("ORDER_SUBMITTED"));
        assertFalse(HtxTrendReplayService.hasExecutedGate("REJECTED"));
        assertFalse(HtxTrendReplayService.hasExecutedGate(null));
    }

    @Test
    void summarizesExitProfilesSeparatelyWithoutMixingTheirProfitAndLoss() {
        HtxTrendReplayTradeEntity full = completeTrade("FULL_EXIT_AT_FIRST_TARGET", "1.20");
        HtxTrendReplayTradeEntity staged = completeTrade("STAGED_EXIT_AT_TARGETS", "0.80");
        HtxTrendReplayTradeEntity runner = completeTrade("STAGED_RUNNER", "1.50");

        HtxTrendReplayService.ReplaySummary summary = HtxTrendReplayService.summarize("replay:test", 1,
                List.of(full, staged, runner));

        assertEquals(new BigDecimal("1.50"), summary.netProfit());
        assertEquals(3L, summary.tradeCount());
        assertEquals(1L, summary.profileSummaries().get("FULL_EXIT_AT_FIRST_TARGET").comparableTradeCount());
        assertEquals(new BigDecimal("0.80"), summary.profileSummaries().get("STAGED_EXIT_AT_TARGETS").netProfit());
        assertEquals(new BigDecimal("1.50"), summary.profileSummaries().get("STAGED_RUNNER").netProfit());
    }

    private static HtxTrendReplayTradeEntity completeTrade(String profile, String netProfit) {
        HtxTrendReplayTradeEntity trade = new HtxTrendReplayTradeEntity();
        trade.setSourceCandidateId(1L);
        trade.setExitProfile(profile);
        trade.setDataIntegrity("COMPLETE");
        trade.setNetProfit(new BigDecimal(netProfit));
        return trade;
    }

    private static List<HtxTrendReplayCandleEntity> candles(String period, List<String> closes, List<Long> barTimes) {
        return java.util.stream.IntStream.range(0, closes.size())
                .mapToObj(index -> candle(period, barTimes.get(index), closes.get(index))).toList();
    }

    private static HtxTrendReplayCandleEntity candle(String period, long barTime, String close) {
        HtxTrendReplayCandleEntity candle = new HtxTrendReplayCandleEntity();
        BigDecimal price = new BigDecimal(close);
        candle.setPeriod(period);
        candle.setBarTime(barTime);
        candle.setOpenPrice(price);
        candle.setHighPrice(price);
        candle.setLowPrice(price);
        candle.setClosePrice(price);
        return candle;
    }
}
