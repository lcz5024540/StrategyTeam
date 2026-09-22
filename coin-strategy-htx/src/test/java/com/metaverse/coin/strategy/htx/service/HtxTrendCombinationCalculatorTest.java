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
    void lowVolatilityStateDoesNotProduceACombination() {
        List<HtxSwapKline> candles = risingRange(40);
        TrendCandidateSignal signal = signal(Direction.LONG, Regime.LOW_VOLATILITY, "108", "107", "90");

        assertEquals(List.of(), calculator.evaluate(candles, signal, 32));
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
