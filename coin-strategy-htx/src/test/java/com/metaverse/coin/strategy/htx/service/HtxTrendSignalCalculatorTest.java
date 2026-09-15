package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSide;
import com.metaverse.coin.strategy.htx.service.HtxTrendSignalCalculator.TrendSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendSignalCalculatorTest {

    private final HtxTrendSignalCalculator calculator = new HtxTrendSignalCalculator();

    @Test
    void breakoutAboveDonchianWithEmaTrendProducesLongSignal() {
        HtxTrendProperties props = looseProperties();
        List<HtxSwapKline> candles = trendingCandles(new BigDecimal("4000"), new BigDecimal("2"), 80);
        candles.add(candle(80, "4190", "4240", "4188", "4235"));

        TrendSignal signal = calculator.evaluate(candles, props);

        assertEquals(TrendSide.LONG, signal.side());
        assertTrue(signal.reason().contains("Donchian high"));
    }

    @Test
    void breakdownBelowDonchianWithEmaTrendProducesShortSignal() {
        HtxTrendProperties props = looseProperties();
        List<HtxSwapKline> candles = trendingCandles(new BigDecimal("4300"), new BigDecimal("-2"), 80);
        candles.add(candle(80, "4110", "4112", "4060", "4065"));

        TrendSignal signal = calculator.evaluate(candles, props);

        assertEquals(TrendSide.SHORT, signal.side());
        assertTrue(signal.reason().contains("Donchian low"));
    }

    @Test
    void lowVolatilityRangeProducesFlatSignal() {
        HtxTrendProperties props = looseProperties();
        props.setMinAtrPct(new BigDecimal("0.005"));
        List<HtxSwapKline> candles = new ArrayList<>();
        for (int i = 0; i < 81; i++) {
            candles.add(candle(i, "4300", "4301", "4299", "4300"));
        }

        TrendSignal signal = calculator.evaluate(candles, props);

        assertEquals(TrendSide.FLAT, signal.side());
        assertTrue(signal.reason().contains("ATR below"));
    }

    @Test
    void higherTimeframeTrendMustAgreeWithEntryBreakout() {
        HtxTrendProperties props = looseProperties();
        List<HtxSwapKline> entryCandles = trendingCandles(new BigDecimal("4000"), new BigDecimal("2"), 80);
        entryCandles.add(candle(80, "4190", "4240", "4188", "4235"));
        List<HtxSwapKline> trendCandles = trendingCandles(new BigDecimal("4300"), new BigDecimal("-2"), 80);

        TrendSignal signal = calculator.evaluate(entryCandles, trendCandles, props, Long.MAX_VALUE);

        assertEquals(TrendSide.FLAT, signal.side());
        assertTrue(signal.reason().contains("higher-timeframe"));
    }

    @Test
    void unfinishedCryptoCandleIsIgnoredBeforeSignalCalculation() {
        HtxTrendProperties props = looseProperties();
        props.setPeriod("15min");
        props.setTrendPeriod("15min");
        props.setCandleCloseGraceSeconds(10L);
        List<HtxSwapKline> candles = new ArrayList<>();
        long periodSeconds = 900L;
        for (int i = 0; i < 80; i++) {
            long id = i * periodSeconds;
            BigDecimal open = new BigDecimal("4000").add(BigDecimal.valueOf(i));
            BigDecimal close = open.add(BigDecimal.ONE);
            candles.add(candle(id, open.toPlainString(), close.add(new BigDecimal("2")).toPlainString(),
                    open.subtract(new BigDecimal("2")).toPlainString(), close.toPlainString()));
        }
        long formingId = 80L * periodSeconds;
        candles.add(candle(formingId, "4090", "4300", "4080", "4295"));

        TrendSignal signal = calculator.evaluate(candles, candles, props, formingId + periodSeconds + 5L);

        assertEquals(TrendSide.FLAT, signal.side());
        assertEquals(79L * periodSeconds, signal.barTime());
    }

    private HtxTrendProperties looseProperties() {
        HtxTrendProperties props = new HtxTrendProperties();
        props.setFastEmaPeriod(5);
        props.setSlowEmaPeriod(10);
        props.setBreakoutLookback(10);
        props.setAtrPeriod(5);
        props.setAdxPeriod(5);
        props.setMinAdx(BigDecimal.ONE);
        props.setMinAtrPct(new BigDecimal("0.0001"));
        return props;
    }

    private List<HtxSwapKline> trendingCandles(BigDecimal start, BigDecimal step, int size) {
        List<HtxSwapKline> candles = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BigDecimal open = start.add(step.multiply(BigDecimal.valueOf(i)));
            BigDecimal close = open.add(step);
            BigDecimal high = open.max(close).add(new BigDecimal("3"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("3"));
            candles.add(candle(i, open.toPlainString(), high.toPlainString(), low.toPlainString(), close.toPlainString()));
        }
        return candles;
    }

    private HtxSwapKline candle(long id, String open, String high, String low, String close) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(new BigDecimal(open));
        candle.setHigh(new BigDecimal(high));
        candle.setLow(new BigDecimal(low));
        candle.setClose(new BigDecimal(close));
        return candle;
    }
}
