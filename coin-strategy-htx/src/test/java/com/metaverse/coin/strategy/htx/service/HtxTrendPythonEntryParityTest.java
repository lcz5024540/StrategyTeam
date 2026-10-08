package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

class HtxTrendPythonEntryParityTest {
    @Test
    void fixedKlineFixtureMatchesDocumentedPythonContractAtExecutionPrecision() {
        assertFixture(uptrend(), HtxTrendEnsembleSignalCalculator.Direction.LONG,
                HtxTrendEnsembleSignalCalculator.Regime.TRENDING_UP, "113.15", "90", "0.217857142857142857",
                "0.012981199641897941", "DONCHIAN_BREAKOUT", "110.80", "116.20", "1.297872340425531915");
        assertFixture(downtrend(), HtxTrendEnsembleSignalCalculator.Direction.SHORT,
                HtxTrendEnsembleSignalCalculator.Regime.TRENDING_DOWN, "106.85", "90", "0.217857142857142857",
                "-0.013388734995383195", "DONCHIAN_BREAKOUT", "109.20", "103.80", "1.297872340425531915");
        assertFixture(flat(), HtxTrendEnsembleSignalCalculator.Direction.FLAT,
                HtxTrendEnsembleSignalCalculator.Regime.LOW_VOLATILITY, "100", "0", "0.020000000000000000",
                "0", null, null, null, null);
    }

    private static void assertFixture(List<HtxSwapKline> candles, HtxTrendEnsembleSignalCalculator.Direction direction,
                                      HtxTrendEnsembleSignalCalculator.Regime regime, String close, String score,
                                      String atr, String roc, String primaryModel, String invalidation,
                                      String target, String expectedR) {
        HtxTrendEnsembleSignalCalculator.TrendCandidateSignal signal = new HtxTrendEnsembleSignalCalculator()
                .evaluate(candles, new HtxTrendEnsembleShadowProperties(), "15min", Long.MAX_VALUE);
        org.junit.jupiter.api.Assertions.assertEquals(direction, signal.direction());
        org.junit.jupiter.api.Assertions.assertEquals(regime, signal.regime());
        equal18(close, signal.closePrice()); equal18(score, signal.signalScore()); equal18(atr, signal.atr()); equal18(roc, signal.momentumPct());
        List<HtxTrendCombinationCalculator.CombinationCandidate> candidates = new HtxTrendCombinationCalculator().evaluate(candles, signal, 32);
        if (primaryModel == null) {
            org.junit.jupiter.api.Assertions.assertTrue(candidates.isEmpty());
            return;
        }
        HtxTrendCombinationCalculator.CombinationCandidate candidate = candidates.stream()
                .filter(item -> primaryModel.equals(item.primaryModel())).findFirst().orElseThrow();
        equal18(invalidation, candidate.invalidationPrice()); equal18(target, candidate.targetZoneLow()); equal18(target, candidate.targetZoneHigh()); equal18(expectedR, candidate.expectedRMultiple());
    }

    private static void equal18(String expected, BigDecimal actual) {
        org.junit.jupiter.api.Assertions.assertEquals(0, new BigDecimal(expected).setScale(18).compareTo(actual.setScale(18, java.math.RoundingMode.HALF_UP)));
    }

    private static List<HtxSwapKline> uptrend() {
        List<HtxSwapKline> rows = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            BigDecimal close = new BigDecimal("100").add(BigDecimal.valueOf(i).movePointLeft(1));
            rows.add(candle((long) i * 900L, close.subtract(new BigDecimal("0.05")), close.add(new BigDecimal("0.10")),
                    close.subtract(new BigDecimal("0.10")), close, new BigDecimal("100").add(BigDecimal.valueOf(i))));
        }
        HtxSwapKline last = rows.get(rows.size() - 1);
        last.setHigh(last.getHigh().add(new BigDecimal("0.25")));
        last.setClose(last.getClose().add(new BigDecimal("0.25")));
        return rows;
    }


    private static List<HtxSwapKline> downtrend() {
        List<HtxSwapKline> rows = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            BigDecimal close = new BigDecimal("120").subtract(BigDecimal.valueOf(i).movePointLeft(1));
            rows.add(candle((long) i * 900L, close.add(new BigDecimal("0.05")), close.add(new BigDecimal("0.10")),
                    close.subtract(new BigDecimal("0.10")), close, new BigDecimal("100").add(BigDecimal.valueOf(i))));
        }
        HtxSwapKline last = rows.get(rows.size() - 1);
        last.setLow(last.getLow().subtract(new BigDecimal("0.25")));
        last.setClose(last.getClose().subtract(new BigDecimal("0.25")));
        return rows;
    }

    private static List<HtxSwapKline> flat() {
        List<HtxSwapKline> rows = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            rows.add(candle((long) i * 900L, new BigDecimal("100"), new BigDecimal("100.01"),
                    new BigDecimal("99.99"), new BigDecimal("100"), BigDecimal.ONE));
        }
        return rows;
    }

    private static HtxSwapKline candle(long id, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal amount) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id); candle.setOpen(open); candle.setHigh(high); candle.setLow(low); candle.setClose(close); candle.setAmount(amount);
        return candle;
    }
}
