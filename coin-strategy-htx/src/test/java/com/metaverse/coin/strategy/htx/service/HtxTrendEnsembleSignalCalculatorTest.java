package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendEnsembleSignalCalculatorTest {

    private final HtxTrendEnsembleSignalCalculator calculator = new HtxTrendEnsembleSignalCalculator();

    @Test
    void bullishStructureCanProduceLongCandidateWithoutFreshBreakout() {
        HtxTrendEnsembleShadowProperties props = looseProperties();
        List<HtxSwapKline> candles = trendingCandles(new BigDecimal("100"), new BigDecimal("1"), 140);
        candles.add(candle(140, "239", "241", "237", "240", "24"));

        TrendCandidateSignal signal = calculator.evaluate(candles, props, "15min", Long.MAX_VALUE);

        assertEquals(Direction.LONG, signal.direction());
        assertEquals(Regime.TRENDING_UP, signal.regime());
        assertTrue(signal.signalScore().compareTo(props.getEntryScoreThreshold()) >= 0);
        assertTrue(signal.entryReason().contains("bullish"));
    }

    @Test
    void bearishStructureCanProduceShortCandidate() {
        HtxTrendEnsembleShadowProperties props = looseProperties();
        List<HtxSwapKline> candles = trendingCandles(new BigDecimal("300"), new BigDecimal("-1"), 140);
        candles.add(candle(140, "161", "163", "158", "159", "24"));

        TrendCandidateSignal signal = calculator.evaluate(candles, props, "30min", Long.MAX_VALUE);

        assertEquals(Direction.SHORT, signal.direction());
        assertEquals(Regime.TRENDING_DOWN, signal.regime());
        assertTrue(signal.signalScore().compareTo(props.getEntryScoreThreshold()) >= 0);
        assertTrue(signal.entryReason().contains("bearish") || signal.entryReason().contains("negative"));
    }

    @Test
    void lowVolatilityRangeStaysFlat() {
        HtxTrendEnsembleShadowProperties props = looseProperties();
        props.setMinAtrPct(new BigDecimal("0.01"));
        List<HtxSwapKline> candles = new ArrayList<>();
        for (int i = 0; i < 140; i++) {
            candles.add(candle(i, "4300", "4301", "4299", "4300", "10"));
        }

        TrendCandidateSignal signal = calculator.evaluate(candles, props, "60min", Long.MAX_VALUE);

        assertEquals(Direction.FLAT, signal.direction());
        assertEquals(Regime.LOW_VOLATILITY, signal.regime());
        assertTrue(signal.invalidationReason().contains("ATR below"));
    }

    @Test
    void unfinishedCryptoCandleIsIgnored() {
        HtxTrendEnsembleShadowProperties props = looseProperties();
        props.setCandleCloseGraceSeconds(10L);
        List<HtxSwapKline> candles = trendingCandles(new BigDecimal("100"), new BigDecimal("1"), 140);
        long formingId = 140L * 900L;
        candles.add(candle(formingId, "250", "280", "249", "279", "100"));

        TrendCandidateSignal signal = calculator.evaluate(candles, props, "15min", formingId + 900L + 5L);

        assertEquals(139L, signal.barTime());
    }

    private HtxTrendEnsembleShadowProperties looseProperties() {
        HtxTrendEnsembleShadowProperties props = new HtxTrendEnsembleShadowProperties();
        props.setFastEmaPeriod(8);
        props.setMediumEmaPeriod(21);
        props.setSlowEmaPeriod(55);
        props.setBreakoutLookback(20);
        props.setMomentumLookback(8);
        props.setAtrPeriod(5);
        props.setAdxPeriod(5);
        props.setVolumeLookback(20);
        props.setMinAtrPct(new BigDecimal("0.0001"));
        props.setMinAdx(BigDecimal.ONE);
        props.setMinMomentumPct(new BigDecimal("0.001"));
        props.setMinVolumeRatio(new BigDecimal("1.05"));
        props.setEntryScoreThreshold(new BigDecimal("60"));
        props.setMinScoreGap(new BigDecimal("10"));
        return props;
    }

    private List<HtxSwapKline> trendingCandles(BigDecimal start, BigDecimal step, int size) {
        List<HtxSwapKline> candles = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BigDecimal open = start.add(step.multiply(BigDecimal.valueOf(i)));
            BigDecimal close = open.add(step);
            BigDecimal high = open.max(close).add(new BigDecimal("2"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("2"));
            candles.add(candle(i, open.toPlainString(), high.toPlainString(), low.toPlainString(),
                    close.toPlainString(), "10"));
        }
        return candles;
    }

    private HtxSwapKline candle(long id, String open, String high, String low, String close, String amount) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(new BigDecimal(open));
        candle.setHigh(new BigDecimal(high));
        candle.setLow(new BigDecimal(low));
        candle.setClose(new BigDecimal(close));
        candle.setAmount(new BigDecimal(amount));
        return candle;
    }
}
