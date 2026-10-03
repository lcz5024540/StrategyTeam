package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Produces explainable combinations from the existing ensemble signal and candle structure.
 * It has no exchange or persistence dependency so replay and live paths use the same rules.
 */
public class HtxTrendCombinationCalculator {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final int FIB_SOURCE_LOOKBACK_BARS = 8;

    public List<CombinationCandidate> evaluate(List<HtxSwapKline> rawCandles,
                                               TrendCandidateSignal signal,
                                               int boxWindowBars) {
        if (rawCandles == null || signal == null || signal.direction() == Direction.FLAT
                || signal.closePrice() == null || signal.atr() == null || signal.atr().signum() <= 0) {
            return List.of();
        }
        List<HtxSwapKline> candles = rawCandles.stream()
                .filter(HtxTrendCombinationCalculator::valid)
                .sorted(Comparator.comparing(HtxSwapKline::getId))
                .toList();
        int boxWindow = Math.max(2, boxWindowBars);
        if (candles.size() < boxWindow + 1) {
            return List.of();
        }
        int last = candles.size() - 1;
        Structure structure = structure(candles, last, boxWindow, signal);
        MarketState marketState = marketState(signal.regime(), signal.atrPct());
        if (marketState.blocked()) {
            return List.of();
        }
        List<CombinationCandidate> candidates = new ArrayList<>();
        boolean boxBreakout = isBoxBreakout(signal.direction(), signal.closePrice(), structure);
        boolean donchianBreakout = isDonchianBreakout(signal.direction(), signal.closePrice(), signal);
        BreakoutEvidence priorBreakout = priorBreakoutEvidence(candles, last, boxWindow, signal.direction());
        boolean fibonacciRetest = isFibonacciRetest(signal.direction(), signal.closePrice(), priorBreakout, signal.emaFast());
        BigDecimal baseScore = signal.signalScore() == null ? BigDecimal.ZERO : signal.signalScore();

        if (boxBreakout && donchianBreakout) {
            candidates.add(candidate("DONCHIAN_BREAKOUT", "BOX_BREAKOUT", signal, structure, marketState,
                    priorBreakout, false, boxWindow, baseScore.add(new BigDecimal("15")),
                    "fresh channel breakout confirmed by box"));
        }
        if (donchianBreakout && !boxBreakout) {
            candidates.add(candidate("DONCHIAN_BREAKOUT", "TREND_CONTEXT", signal, structure, marketState,
                    priorBreakout, false, boxWindow, baseScore.add(new BigDecimal("12")),
                    "fresh channel breakout aligned with trend context"));
        }
        if ((boxBreakout || priorBreakout.boxBreakout()) && fibonacciRetest) {
            candidates.add(candidate("FIB_PULLBACK_CONTINUATION", "BOX_BREAKOUT", signal, structure, marketState,
                    priorBreakout, true, boxWindow, baseScore.add(new BigDecimal("10")),
                    "Fibonacci retest confirmed by prior box breakout"));
        }
        if ((donchianBreakout || priorBreakout.donchianBreakout()) && fibonacciRetest) {
            candidates.add(candidate("FIB_PULLBACK_CONTINUATION", "DONCHIAN_BREAKOUT", signal, structure, marketState,
                    priorBreakout, true, boxWindow, baseScore.add(new BigDecimal("10")),
                    "Fibonacci retest confirmed by prior channel breakout"));
        }
        if ((boxBreakout || priorBreakout.boxBreakout()) && (donchianBreakout || priorBreakout.donchianBreakout())
                && fibonacciRetest) {
            candidates.add(candidate("FIB_PULLBACK_CONTINUATION", "BOX_BREAKOUT,DONCHIAN_BREAKOUT", signal,
                    structure, marketState, priorBreakout, true, boxWindow, baseScore.add(new BigDecimal("20")),
                    "Fibonacci retest confirmed by box and channel"));
        }
        if (emaMomentumContinuation(signal)) {
            candidates.add(candidate("EMA_VWAP_MOMENTUM_CONTINUATION", "EMA_STACK,MOMENTUM", signal,
                    structure, marketState, priorBreakout, false, boxWindow, baseScore.add(new BigDecimal("12")),
                    "EMA stack and momentum continuation"));
        }
        return candidates.stream().sorted(Comparator.comparing(CombinationCandidate::score).reversed()).toList();
    }

    private CombinationCandidate candidate(String primaryModel, String confirmationModels,
                                           TrendCandidateSignal signal, Structure structure, MarketState marketState,
                                           BreakoutEvidence priorBreakout, boolean emaReclaimConfirmed,
                                           int boxWindowBars, BigDecimal score, String hypothesis) {
        BigDecimal invalidation = invalidation(primaryModel, signal, structure);
        Targets targets = targets(signal.direction(), structure);
        BigDecimal reward = signal.direction() == Direction.LONG
                ? targets.targetZoneLow().subtract(signal.closePrice()) : signal.closePrice().subtract(targets.targetZoneHigh());
        BigDecimal risk = signal.direction() == Direction.LONG
                ? signal.closePrice().subtract(invalidation) : invalidation.subtract(signal.closePrice());
        BigDecimal expectedR = risk.signum() > 0 ? reward.max(BigDecimal.ZERO).divide(risk, 18, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal momentumQuality = score.min(ONE_HUNDRED).add(signal.adx() == null ? BigDecimal.ZERO : signal.adx())
                .divide(new BigDecimal("2"), 18, java.math.RoundingMode.HALF_UP).min(ONE_HUNDRED);
        FibonacciEvidence fibonacciEvidence = "FIB_PULLBACK_CONTINUATION".equals(primaryModel)
                ? new FibonacciEvidence(priorBreakout.breakoutBarTime(), priorBreakout.impulseHigh(),
                priorBreakout.impulseLow(), priorBreakout.fibonacciLower(), priorBreakout.fibonacciUpper(),
                emaReclaimConfirmed, boxWindowBars, FIB_SOURCE_LOOKBACK_BARS)
                : FibonacciEvidence.notApplicable(boxWindowBars, FIB_SOURCE_LOOKBACK_BARS);
        return new CombinationCandidate(primaryModel, confirmationModels, signal.direction(), score.min(ONE_HUNDRED),
                marketState.name(), structure.boxHigh(), structure.boxLow(), structure.swingHigh(), structure.swingLow(),
                structure.fibonacciLower(), structure.fibonacciUpper(), invalidation, targets.targetZoneLow(),
                targets.targetZoneHigh(), targets.boxMeasuredMoveTarget(), targets.fibExtension1272(),
                targets.fibExtension1618(), expectedR, momentumQuality, hypothesis, fibonacciEvidence);
    }

    private static Targets targets(Direction direction, Structure structure) {
        BigDecimal range = structure.boxHigh().subtract(structure.boxLow());
        BigDecimal swingRange = structure.swingHigh().subtract(structure.swingLow());
        BigDecimal measured = direction == Direction.LONG ? structure.boxHigh().add(range) : structure.boxLow().subtract(range);
        BigDecimal extension1272 = direction == Direction.LONG
                ? structure.swingLow().add(swingRange.multiply(new BigDecimal("1.272")))
                : structure.swingHigh().subtract(swingRange.multiply(new BigDecimal("1.272")));
        BigDecimal extension1618 = direction == Direction.LONG
                ? structure.swingLow().add(swingRange.multiply(new BigDecimal("1.618")))
                : structure.swingHigh().subtract(swingRange.multiply(new BigDecimal("1.618")));
        BigDecimal zoneLow = measured.min(extension1272).min(extension1618);
        BigDecimal zoneHigh = measured.max(extension1272).max(extension1618);
        return new Targets(zoneLow, zoneHigh, measured, extension1272, extension1618);
    }

    private static BigDecimal invalidation(String primaryModel, TrendCandidateSignal signal, Structure structure) {
        if ("FIB_PULLBACK_CONTINUATION".equals(primaryModel)) {
            return signal.direction() == Direction.LONG ? structure.fibonacciLower() : structure.fibonacciUpper();
        }
        if ("DONCHIAN_BREAKOUT".equals(primaryModel)) {
            return signal.direction() == Direction.LONG ? signal.breakoutLow() : signal.breakoutHigh();
        }
        return signal.direction() == Direction.LONG ? structure.boxLow() : structure.boxHigh();
    }

    private static boolean valid(HtxSwapKline candle) {
        return candle != null && candle.getId() != null && positive(candle.getHigh())
                && positive(candle.getLow()) && positive(candle.getClose());
    }

    private static Structure structure(List<HtxSwapKline> candles, int last, int boxWindow, TrendCandidateSignal signal) {
        List<HtxSwapKline> prior = candles.subList(last - boxWindow, last);
        BigDecimal boxHigh = prior.stream().map(HtxSwapKline::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal boxLow = prior.stream().map(HtxSwapKline::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal swingHigh = candles.subList(Math.max(0, last - boxWindow), last + 1).stream()
                .map(HtxSwapKline::getHigh).max(BigDecimal::compareTo).orElse(boxHigh);
        BigDecimal swingLow = candles.subList(Math.max(0, last - boxWindow), last + 1).stream()
                .map(HtxSwapKline::getLow).min(BigDecimal::compareTo).orElse(boxLow);
        BigDecimal range = swingHigh.subtract(swingLow);
        BigDecimal fibonacciLower = swingHigh.subtract(range.multiply(new BigDecimal("0.618")));
        BigDecimal fibonacciUpper = swingHigh.subtract(range.multiply(new BigDecimal("0.382")));
        return new Structure(boxHigh, boxLow, swingHigh, swingLow, fibonacciLower, fibonacciUpper);
    }

    private static boolean isBoxBreakout(Direction direction, BigDecimal close, Structure structure) {
        return direction == Direction.LONG ? close.compareTo(structure.boxHigh()) > 0
                : close.compareTo(structure.boxLow()) < 0;
    }

    private static boolean isDonchianBreakout(Direction direction, BigDecimal close, TrendCandidateSignal signal) {
        return direction == Direction.LONG ? close.compareTo(signal.breakoutHigh()) > 0
                : close.compareTo(signal.breakoutLow()) < 0;
    }

    private static boolean emaMomentumContinuation(TrendCandidateSignal signal) {
        if (signal.emaFast() == null || signal.emaMedium() == null || signal.emaSlow() == null
                || signal.momentumPct() == null || signal.adx() == null) {
            return false;
        }
        boolean longAligned = signal.emaFast().compareTo(signal.emaMedium()) > 0
                && signal.emaMedium().compareTo(signal.emaSlow()) > 0 && signal.momentumPct().signum() > 0;
        boolean shortAligned = signal.emaFast().compareTo(signal.emaMedium()) < 0
                && signal.emaMedium().compareTo(signal.emaSlow()) < 0 && signal.momentumPct().signum() < 0;
        return signal.adx().compareTo(new BigDecimal("20")) >= 0
                && ((signal.direction() == Direction.LONG && longAligned)
                || (signal.direction() == Direction.SHORT && shortAligned));
    }

    /**
     * A Fibonacci continuation must be based on an earlier confirmed breakout.  It cannot
     * use the same candle as both a new breakout and a pullback: that was the source of an
     * impossible BOX+FIB/DONCHIAN+FIB condition in the first draft.
     */
    private static boolean isFibonacciRetest(Direction direction, BigDecimal close, BreakoutEvidence evidence,
                                             BigDecimal emaFast) {
        if (!positive(emaFast) || evidence == null || !evidence.hasBreakout()) {
            return false;
        }
        if (direction == Direction.LONG) {
            return close.compareTo(evidence.fibonacciLower()) >= 0 && close.compareTo(evidence.fibonacciUpper()) <= 0
                    && close.compareTo(emaFast) >= 0;
        }
        return close.compareTo(evidence.fibonacciLower()) >= 0 && close.compareTo(evidence.fibonacciUpper()) <= 0
                && close.compareTo(emaFast) <= 0;
    }

    private static BreakoutEvidence priorBreakoutEvidence(List<HtxSwapKline> candles, int last, int boxWindow,
                                                            Direction direction) {
        int first = Math.max(boxWindow, last - FIB_SOURCE_LOOKBACK_BARS);
        for (int index = last - 1; index >= first; index--) {
            List<HtxSwapKline> prior = candles.subList(index - boxWindow, index);
            BigDecimal high = prior.stream().map(HtxSwapKline::getHigh).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal low = prior.stream().map(HtxSwapKline::getLow).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            HtxSwapKline breakout = candles.get(index);
            boolean box = direction == Direction.LONG ? breakout.getClose().compareTo(high) > 0
                    : breakout.getClose().compareTo(low) < 0;
            BigDecimal donHigh = prior.stream().map(HtxSwapKline::getHigh).max(BigDecimal::compareTo).orElse(high);
            BigDecimal donLow = prior.stream().map(HtxSwapKline::getLow).min(BigDecimal::compareTo).orElse(low);
            boolean donchian = direction == Direction.LONG ? breakout.getClose().compareTo(donHigh) > 0
                    : breakout.getClose().compareTo(donLow) < 0;
            if (!box && !donchian) {
                continue;
            }
            BigDecimal impulseHigh = candles.subList(index, last).stream().map(HtxSwapKline::getHigh)
                    .max(BigDecimal::compareTo).orElse(breakout.getHigh());
            BigDecimal impulseLow = candles.subList(index, last).stream().map(HtxSwapKline::getLow)
                    .min(BigDecimal::compareTo).orElse(breakout.getLow());
            BigDecimal range = impulseHigh.subtract(impulseLow);
            if (range.signum() <= 0) {
                continue;
            }
            if (direction == Direction.LONG) {
                return new BreakoutEvidence(box, donchian,
                        breakout.getId(), impulseHigh, impulseLow,
                        impulseHigh.subtract(range.multiply(new BigDecimal("0.618"))),
                        impulseHigh.subtract(range.multiply(new BigDecimal("0.382"))));
            }
            return new BreakoutEvidence(box, donchian,
                    breakout.getId(), impulseHigh, impulseLow,
                    impulseLow.add(range.multiply(new BigDecimal("0.382"))),
                    impulseLow.add(range.multiply(new BigDecimal("0.618"))));
        }
        return BreakoutEvidence.none();
    }

    private static MarketState marketState(Regime regime, BigDecimal atrPct) {
        if (regime == Regime.LOW_VOLATILITY || atrPct == null || atrPct.signum() <= 0) {
            return MarketState.LOW_VOL_DEAD;
        }
        if (regime == Regime.TRENDING_UP) {
            return MarketState.BROAD_UP;
        }
        if (regime == Regime.TRENDING_DOWN) {
            return MarketState.BROAD_DOWN;
        }
        return MarketState.CHOP;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public enum MarketState {
        BROAD_UP(false), BROAD_DOWN(false), MIXED(false), CHOP(true), HIGH_VOL_RISK(true), LOW_VOL_DEAD(true);

        private final boolean blocked;

        MarketState(boolean blocked) {
            this.blocked = blocked;
        }

        public boolean blocked() {
            return blocked;
        }
    }

    public record CombinationCandidate(String primaryModel, String confirmationModels, Direction direction,
                                       BigDecimal score, String marketRegime, BigDecimal boxHigh, BigDecimal boxLow,
                                       BigDecimal swingHigh, BigDecimal swingLow, BigDecimal fibonacciLower,
                                       BigDecimal fibonacciUpper, BigDecimal invalidationPrice, BigDecimal targetZoneLow,
                                       BigDecimal targetZoneHigh, BigDecimal boxMeasuredMoveTarget, BigDecimal fibExtension1272,
                                       BigDecimal fibExtension1618, BigDecimal expectedRMultiple,
                                       BigDecimal momentumQualityScore, String hypothesis,
                                       FibonacciEvidence fibonacciEvidence) {
        public CombinationCandidate withScore(BigDecimal adjustedScore) {
            return new CombinationCandidate(primaryModel, confirmationModels, direction, adjustedScore, marketRegime,
                    boxHigh, boxLow, swingHigh, swingLow, fibonacciLower, fibonacciUpper, invalidationPrice,
                    targetZoneLow, targetZoneHigh, boxMeasuredMoveTarget, fibExtension1272, fibExtension1618,
                    expectedRMultiple, momentumQualityScore, hypothesis, fibonacciEvidence);
        }
    }

    private record Structure(BigDecimal boxHigh, BigDecimal boxLow, BigDecimal swingHigh, BigDecimal swingLow,
                             BigDecimal fibonacciLower, BigDecimal fibonacciUpper) {
    }

    private record BreakoutEvidence(boolean boxBreakout, boolean donchianBreakout, Long breakoutBarTime,
                                    BigDecimal impulseHigh, BigDecimal impulseLow,
                                    BigDecimal fibonacciLower, BigDecimal fibonacciUpper) {
        static BreakoutEvidence none() {
            return new BreakoutEvidence(false, false, null, null, null, null, null);
        }

        boolean hasBreakout() {
            return boxBreakout || donchianBreakout;
        }
    }

    /** Exact prior-breakout evidence used by the Fibonacci retracement gate. */
    public record FibonacciEvidence(Long breakoutBarTime, BigDecimal impulseHigh, BigDecimal impulseLow,
                                    BigDecimal retracementLower, BigDecimal retracementUpper,
                                    boolean emaReclaimConfirmed, int boxWindowBars, int sourceLookbackBars) {
        static FibonacciEvidence notApplicable(int boxWindowBars, int sourceLookbackBars) {
            return new FibonacciEvidence(null, null, null, null, null, false, boxWindowBars, sourceLookbackBars);
        }
    }

    private record Targets(BigDecimal targetZoneLow, BigDecimal targetZoneHigh, BigDecimal boxMeasuredMoveTarget,
                           BigDecimal fibExtension1272, BigDecimal fibExtension1618) { }
}
