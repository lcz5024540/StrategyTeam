package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationCalculator.CombinationCandidate;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HtxTrendCombinationCalculatorTest {

    private final HtxTrendCombinationCalculator calculator = new HtxTrendCombinationCalculator();

    @Test
    void boxAndDonchianBreakoutBecomeOneHighestPriorityCombination() {
        List<HtxSwapKline> candles = risingRange(40);
        candles.add(candle(40, "108", "112", "107", "111"));
        TrendCandidateSignal signal = signal(Direction.LONG, Regime.TRENDING_UP, "111", "110", "90");

        List<CombinationCandidate> candidates = calculator.evaluate(candles, signal, 32);

        assertFalse(candidates.isEmpty());
        CombinationCandidate candidate = candidates.get(0);
        assertEquals("DONCHIAN_BREAKOUT", candidate.primaryModel());
        assertEquals("BOX_BREAKOUT", candidate.confirmationModels());
        assertEquals("BROAD_UP", candidate.marketRegime());
        assertEquals(new BigDecimal("100"), candidate.score());
        assertEquals(new BigDecimal("99"), candidate.invalidationPrice());
    }

    @Test
    void donchianBreakoutCanBeAnIndependentTrendStartWithoutBoxConfirmation() {
        List<HtxSwapKline> candles = flatRange(32, "115", "100", "108");
        candles.add(candle(32, "108", "113", "107", "112"));
        TrendCandidateSignal signal = signal(Direction.LONG, Regime.TRENDING_UP, "112", "110", "90");

        List<CombinationCandidate> candidates = calculator.evaluate(candles, signal, 32);

        CombinationCandidate candidate = candidates.stream()
                .filter(item -> "DONCHIAN_BREAKOUT".equals(item.primaryModel()))
                .findFirst().orElseThrow();
        assertEquals("TREND_CONTEXT", candidate.confirmationModels());
        assertEquals(new BigDecimal("100"), candidate.score());
        assertEquals(new BigDecimal("99"), candidate.invalidationPrice());
        assertEquals(candidate.boxMeasuredMoveTarget(), candidate.targetZoneLow());
        assertEquals(candidate.boxMeasuredMoveTarget(), candidate.targetZoneHigh());
    }

    @Test
    void emaMomentumContinuationRemainsAnIndependentCandidate() {
        List<HtxSwapKline> candles = flatRange(32, "115", "100", "108");
        candles.add(candle(32, "108", "109", "107", "108"));
        TrendCandidateSignal signal = signal(Direction.LONG, Regime.TRENDING_UP, "108", "120", "80");

        List<CombinationCandidate> candidates = calculator.evaluate(candles, signal, 32);

        CombinationCandidate candidate = candidates.stream()
                .filter(item -> "EMA_VWAP_MOMENTUM_CONTINUATION".equals(item.primaryModel()))
                .findFirst().orElseThrow();
        assertEquals("EMA_STACK,MOMENTUM", candidate.confirmationModels());
        assertEquals(new BigDecimal("92"), candidate.score());
        assertEquals(new BigDecimal("102"), candidate.invalidationPrice());
        assertEquals(candidate.swingHigh(), candidate.targetZoneLow());
        assertEquals(candidate.swingHigh(), candidate.targetZoneHigh());
    }

    @Test
    void lowVolatilityStateDoesNotProduceACombination() {
        List<HtxSwapKline> candles = risingRange(40);
        TrendCandidateSignal signal = signal(Direction.LONG, Regime.LOW_VOLATILITY, "108", "107", "90");

        assertEquals(List.of(), calculator.evaluate(candles, signal, 32));
    }

    @Test
    void trendingRegimePreconditionIsRetainedByDefaultAndCanBeExplicitlyDisabledForAblation() {
        List<HtxSwapKline> candles = risingRange(40);
        candles.add(candle(40, "108", "112", "107", "111"));
        TrendCandidateSignal rangeSignal = signal(Direction.LONG, Regime.RANGE, "111", "110", "90");

        assertEquals(List.of(), calculator.evaluate(candles, rangeSignal, 32));
        assertFalse(calculator.evaluate(candles, rangeSignal, 32, false).isEmpty());
    }

    @Test
    void fibonacciRetestRetainsThePriorBreakoutAndImpulseEvidence() {
        List<HtxSwapKline> candles = risingRange(32);
        candles.add(candle(32, "106", "121", "107", "120"));
        for (int index = 33; index < 40; index++) {
            candles.add(candle(index, "116", "120", "112", "116"));
        }
        candles.add(candle(40, "115", "117", "113", "114"));
        TrendCandidateSignal signal = signal(Direction.LONG, Regime.TRENDING_UP, "114", "120", "90");

        List<CombinationCandidate> candidates = calculator.evaluate(candles, signal, 32);

        assertFalse(candidates.isEmpty());
        CombinationCandidate candidate = candidates.get(0);
        assertEquals("FIB_PULLBACK_CONTINUATION", candidate.primaryModel());
        assertEquals(32L, candidate.fibonacciEvidence().breakoutBarTime());
        assertEquals(new BigDecimal("121"), candidate.fibonacciEvidence().impulseHigh());
        assertEquals(new BigDecimal("107"), candidate.fibonacciEvidence().impulseLow());
        assertEquals(new BigDecimal("112.348"), candidate.fibonacciEvidence().retracementLower());
        assertEquals(new BigDecimal("115.652"), candidate.fibonacciEvidence().retracementUpper());
        assertEquals(true, candidate.fibonacciEvidence().emaReclaimConfirmed());
        assertEquals(candidate.fibExtension1272().min(candidate.fibExtension1618()), candidate.targetZoneLow());
        assertEquals(candidate.fibExtension1272().max(candidate.fibExtension1618()), candidate.targetZoneHigh());
        assertEquals(0, new BigDecimal("0.25").compareTo(candidate.entryCandleQuality().bodyToRange()));
        assertEquals(0, new BigDecimal("0.5").compareTo(candidate.entryCandleQuality().upperWickToRange()));
        assertEquals(0, new BigDecimal("0.25").compareTo(candidate.entryCandleQuality().lowerWickToRange()));
    }

    private TrendCandidateSignal signal(Direction direction, Regime regime, String close, String breakoutHigh, String score) {
        return new TrendCandidateSignal("15min", 40L, direction, regime, new BigDecimal(score), new BigDecimal(score),
                BigDecimal.ZERO, new BigDecimal(score), new BigDecimal(close), new BigDecimal("106"),
                new BigDecimal("104"), new BigDecimal("102"), new BigDecimal(breakoutHigh), new BigDecimal("99"),
                new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("30"), new BigDecimal("0.02"),
                new BigDecimal("1.5"), "test", "valid");
    }

    private List<HtxSwapKline> risingRange(int size) {
        List<HtxSwapKline> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            rows.add(candle(i, "100", "110", "101", "105"));
        }
        return rows;
    }

    private List<HtxSwapKline> flatRange(int size, String high, String low, String close) {
        List<HtxSwapKline> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            rows.add(candle(i, close, high, low, close));
        }
        return rows;
    }

    private HtxSwapKline candle(long id, String open, String high, String low, String close) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(new BigDecimal(open));
        candle.setHigh(new BigDecimal(high));
        candle.setLow(new BigDecimal(low));
        candle.setClose(new BigDecimal(close));
        candle.setAmount(BigDecimal.ONE);
        return candle;
    }
}
