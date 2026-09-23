package com.metaverse.coin.strategy.htx.replay;

import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendCandidateSignalMapper;
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
    void replaySelectionRetainsLegacyCandidatesWithoutFinalGate() throws NoSuchMethodException {
        Select select = HtxTrendCandidateSignalMapper.class
                .getMethod("findReplayCandidatesBetween", long.class, long.class)
                .getAnnotation(Select.class);

        assertNotNull(select);
        String statement = select.value()[0];
        assertTrue(statement.contains("bar_time >= #{startBarTime}"));
        assertTrue(statement.contains("bar_time <= #{endBarTime}"));
        assertFalse(statement.contains("gate_result = 'PASSED'"));
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
