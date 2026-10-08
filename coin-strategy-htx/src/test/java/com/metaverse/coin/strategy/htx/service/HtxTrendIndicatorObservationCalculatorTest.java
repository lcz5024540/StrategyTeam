package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendIndicatorObservationCalculatorTest {

    private final HtxTrendIndicatorObservationCalculator calculator = new HtxTrendIndicatorObservationCalculator();

    @Test
    void recordsRsiMacdAndParticipationFromCompletedCandles() {
        List<HtxSwapKline> candles = acceleratingCandles(40);

        HtxTrendIndicatorObservationCalculator.Observation observation = calculator.observe(candles, 39L);

        assertEquals("AVAILABLE", observation.status());
        assertEquals(39L, observation.barTime());
        assertEquals("AVAILABLE", observation.rsi().status());
        assertTrue(observation.rsi().value().compareTo(new BigDecimal("70")) > 0);
        assertEquals("AVAILABLE", observation.macd().status());
        assertNotNull(observation.macd().histogram());
        assertEquals("AVAILABLE", observation.participation().status());
        assertTrue(observation.participation().volumeRatio().compareTo(BigDecimal.ONE) > 0);
        assertEquals("UP_MOVE_CONFIRMED", observation.participation().confirmation());
        assertEquals("AVAILABLE", observation.priceAction().status());
    }

    @Test
    void excludesTheStillFormingCandleUsingTheSignalBarTime() {
        List<HtxSwapKline> candles = acceleratingCandles(40);
        candles.add(candle(40L, "100", "500", "1", "400", "100000", "1000000"));

        HtxTrendIndicatorObservationCalculator.Observation observation = calculator.observe(candles, 39L);

        assertEquals(39L, observation.barTime());
        assertEquals(candles.get(39).getClose(), observation.close());
        assertTrue(observation.participation().volumeRatio().compareTo(new BigDecimal("10")) < 0);
    }

    @Test
    void returnsExplicitUnavailableEvidenceForShortWindows() {
        HtxTrendIndicatorObservationCalculator.Observation observation = calculator.observe(acceleratingCandles(20), 19L);

        assertEquals("UNAVAILABLE", observation.status());
        assertEquals("UNAVAILABLE", observation.rsi().status());
        assertEquals("UNAVAILABLE", observation.macd().status());
    }

    @Test
    void recordsTurnoverOnlyParticipationAsPartialInsteadOfInventingVolume() {
        List<HtxSwapKline> candles = acceleratingCandles(40);
        candles.forEach(candle -> candle.setVol(null));

        HtxTrendIndicatorObservationCalculator.Observation observation = calculator.observe(candles, 39L);

        assertEquals("PARTIAL", observation.participation().status());
        assertEquals(null, observation.participation().volumeRatio());
        assertNotNull(observation.participation().turnoverRatio());
        assertEquals("UP_MOVE_CONFIRMED_BY_TURNOVER", observation.participation().confirmation());
        assertTrue(observation.participation().reason().contains("turnover"));
    }

    private static List<HtxSwapKline> acceleratingCandles(int size) {
        List<HtxSwapKline> candles = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            BigDecimal open = new BigDecimal("100").add(BigDecimal.valueOf((long) index * index));
            BigDecimal close = open.add(BigDecimal.valueOf(index + 1L));
            BigDecimal volume = BigDecimal.valueOf(100L + index);
            if (index == size - 1) volume = volume.multiply(BigDecimal.valueOf(3L));
            candles.add(candle(index, open.toPlainString(), close.add(BigDecimal.ONE).toPlainString(),
                    open.subtract(BigDecimal.ONE).toPlainString(), close.toPlainString(), volume.toPlainString(),
                    volume.multiply(close).toPlainString()));
        }
        return candles;
    }

    private static HtxSwapKline candle(long id, String open, String high, String low, String close,
                                       String volume, String turnover) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(new BigDecimal(open));
        candle.setHigh(new BigDecimal(high));
        candle.setLow(new BigDecimal(low));
        candle.setClose(new BigDecimal(close));
        candle.setVol(new BigDecimal(volume));
        candle.setTradeTurnover(new BigDecimal(turnover));
        return candle;
    }
}
