package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendModelCoverageCalculatorTest {
    private final HtxTrendModelCoverageCalculator calculator = new HtxTrendModelCoverageCalculator();

    @Test
    void marksFreshDonchianWithoutRequiringNestedBoxEvidence() {
        List<HtxSwapKline> candles = rising(42);
        HtxSwapKline last = candles.get(candles.size() - 1);
        last.setClose(new BigDecimal("200")); last.setHigh(new BigDecimal("202")); last.setLow(new BigDecimal("198"));
        TrendCandidateSignal signal = signal(last.getId(), last.getClose(), new BigDecimal("180"), new BigDecimal("120"));

        HtxTrendModelCoverageCalculator.Coverage coverage = calculator.evaluate(candles, signal);

        assertTrue(coverage.available());
        assertTrue(coverage.donchian());
        assertTrue(coverage.activeModels().contains("DONCHIAN_BREAKOUT"));
    }

    @Test
    void unavailableInputNeverPretendsToProduceAnEntryRoute() {
        HtxTrendModelCoverageCalculator.Coverage coverage = calculator.evaluate(List.of(), signal(1L, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO));
        assertTrue(!coverage.available());
        assertTrue(coverage.activeModels().isEmpty());
    }

    @Test
    void forwardOutcomeKeepsEachActiveModelIndependentAndDeductsBothFees() {
        List<HtxSwapKline> candles = rising(42);
        HtxSwapKline last = candles.get(candles.size() - 1);
        last.setClose(new BigDecimal("200")); last.setHigh(new BigDecimal("202")); last.setLow(new BigDecimal("198"));
        HtxTrendModelCoverageCalculator.Coverage coverage = calculator.evaluate(candles,
                signal(last.getId(), last.getClose(), new BigDecimal("180"), new BigDecimal("120")));

        List<HtxTrendModelCoverageCalculator.ForwardOutcome> outcomes = calculator.evaluateForwardOutcomes(
                coverage, new BigDecimal("200"), new BigDecimal("204"), 4,
                new BigDecimal("0.0002"), new BigDecimal("0.0003"));

        assertEquals(2, outcomes.size());
        HtxTrendModelCoverageCalculator.ForwardOutcome outcome = outcomes.stream()
                .filter(item -> "DONCHIAN_BREAKOUT".equals(item.model())).findFirst().orElseThrow();
        assertEquals("DONCHIAN_BREAKOUT", outcome.model());
        assertEquals(0, new BigDecimal("0.02").compareTo(outcome.grossReturnRate()));
        assertEquals(0, new BigDecimal("0.0005").compareTo(outcome.roundTripFeeRate()));
        assertEquals(0, new BigDecimal("0.0195").compareTo(outcome.netReturnRate()));
    }

    private static TrendCandidateSignal signal(long barTime, BigDecimal close, BigDecimal high, BigDecimal low) {
        return new TrendCandidateSignal("15min", barTime, Direction.LONG, Regime.TRENDING_UP, new BigDecimal("80"),
                new BigDecimal("80"), BigDecimal.ZERO, new BigDecimal("80"), close, new BigDecimal("190"),
                new BigDecimal("170"), new BigDecimal("150"), high, low, new BigDecimal("4"), new BigDecimal("0.02"),
                new BigDecimal("25"), new BigDecimal("0.02"), new BigDecimal("1.2"), "test", "test");
    }

    private static List<HtxSwapKline> rising(int size) {
        List<HtxSwapKline> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            BigDecimal open = new BigDecimal("100").add(BigDecimal.valueOf(i));
            HtxSwapKline candle = new HtxSwapKline();
            candle.setId((long) i); candle.setOpen(open); candle.setClose(open.add(BigDecimal.ONE));
            candle.setHigh(open.add(new BigDecimal("2"))); candle.setLow(open.subtract(BigDecimal.ONE));
            candle.setAmount(new BigDecimal("100")); result.add(candle);
        }
        return result;
    }
}
