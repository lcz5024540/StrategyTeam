package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only classifier for independent trend-entry families.  It deliberately does not return a
 * tradable order: its only purpose is to measure coverage and overlap before a model is allowed
 * into the live router.
 */
public class HtxTrendModelCoverageCalculator {
    private static final int DONCHIAN_BARS = 20;
    private static final int FIB_SOURCE_BARS = 8;
    private static final int VWAP_BARS = 20;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal THREE = new BigDecimal("3");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    public Coverage evaluate(List<HtxSwapKline> rawCandles, TrendCandidateSignal signal) {
        if (rawCandles == null || signal == null || signal.barTime() == null || !positive(signal.closePrice())) {
            return Coverage.unavailable();
        }
        List<HtxSwapKline> candles = rawCandles.stream().filter(HtxTrendModelCoverageCalculator::valid)
                .filter(item -> item.getId() <= signal.barTime()).sorted(Comparator.comparing(HtxSwapKline::getId)).toList();
        if (candles.size() < 2 * DONCHIAN_BARS + 2) {
            return Coverage.unavailable();
        }
        int last = candles.size() - 1;
        Direction context = context(signal);
        boolean donchian = context != Direction.FLAT && brokeChannel(context, signal.closePrice(), signal);
        boolean fibonacci = context != Direction.FLAT && fibonacciRetest(candles, last, context, signal.closePrice(), signal.emaFast());
        BigDecimal vwap = vwap(candles, last, VWAP_BARS);
        boolean emaVwap = context != Direction.FLAT && emaVwapPullback(context, signal, vwap);
        boolean momentum = context != Direction.FLAT && momentumContinuation(context, signal);
        boolean squeeze = context != Direction.FLAT && squeeze(candles, last, signal.atr());
        boolean structure = context != Direction.FLAT && structureAligned(candles, last, context);
        String route = donchian ? "DONCHIAN_BREAKOUT" : fibonacci ? "FIB_PULLBACK_CONTINUATION"
                : emaVwap && momentum ? "EMA_MOMENTUM_CONTINUATION" : "NO_ENTRY_ROUTE";
        return new Coverage(true, context, donchian, fibonacci, emaVwap, momentum, squeeze, structure, route, vwap);
    }

    /**
     * Evaluates each independently observable model instead of turning overlapping evidence into
     * a synthetic vote.  The caller may later compare their realised forward return on the same
     * entry price and fee basis.  Squeeze and structure stay evidence-only: they can be useful
     * context, but are not silently promoted into an entry family here.
     */
    public List<String> activeEntryModels(Coverage coverage) {
        if (coverage == null || !coverage.available() || coverage.direction() == Direction.FLAT) {
            return List.of();
        }
        List<String> models = new ArrayList<>();
        if (coverage.donchian()) models.add("DONCHIAN_BREAKOUT");
        if (coverage.fibonacci()) models.add("FIB_PULLBACK_CONTINUATION");
        if (coverage.emaVwap()) models.add("EMA_VWAP_PULLBACK");
        if (coverage.momentum()) models.add("MOMENTUM_CONTINUATION");
        if (coverage.emaVwap() && coverage.momentum()) models.add("EMA_VWAP_MOMENTUM_CONTINUATION");
        return List.copyOf(models);
    }

    /**
     * Marks a forward close-to-close return for every independent model that was active at the
     * source bar.  This is an observation result, never an execution instruction.  Open and
     * close fee rates are kept separate because HTX can return different account-specific rates.
     */
    public List<ForwardOutcome> evaluateForwardOutcomes(Coverage coverage, BigDecimal entryPrice,
                                                         BigDecimal futureClose, int horizonBars,
                                                         BigDecimal openTakerFeeRate,
                                                         BigDecimal closeTakerFeeRate) {
        if (!positive(entryPrice) || !positive(futureClose) || horizonBars <= 0
                || coverage == null || coverage.direction() == Direction.FLAT) {
            return List.of();
        }
        BigDecimal grossReturn = coverage.direction() == Direction.LONG
                ? futureClose.subtract(entryPrice).divide(entryPrice, 18, RoundingMode.HALF_UP)
                : entryPrice.subtract(futureClose).divide(entryPrice, 18, RoundingMode.HALF_UP);
        BigDecimal roundTripFeeRate = nonNegative(openTakerFeeRate).add(nonNegative(closeTakerFeeRate));
        BigDecimal netReturn = grossReturn.subtract(roundTripFeeRate);
        List<ForwardOutcome> outcomes = new ArrayList<>();
        for (String model : activeEntryModels(coverage)) {
            outcomes.add(new ForwardOutcome(model, coverage.direction(), horizonBars, entryPrice, futureClose,
                    grossReturn, roundTripFeeRate, netReturn));
        }
        return List.copyOf(outcomes);
    }

    private static Direction context(TrendCandidateSignal signal) {
        if (signal.direction() != Direction.FLAT) return signal.direction();
        if (positive(signal.emaFast()) && positive(signal.emaMedium()) && positive(signal.emaSlow())) {
            if (signal.emaFast().compareTo(signal.emaMedium()) > 0 && signal.emaMedium().compareTo(signal.emaSlow()) > 0) return Direction.LONG;
            if (signal.emaFast().compareTo(signal.emaMedium()) < 0 && signal.emaMedium().compareTo(signal.emaSlow()) < 0) return Direction.SHORT;
        }
        return Direction.FLAT;
    }

    private static boolean brokeChannel(Direction side, BigDecimal close, TrendCandidateSignal signal) {
        return side == Direction.LONG ? close.compareTo(signal.breakoutHigh()) > 0 : close.compareTo(signal.breakoutLow()) < 0;
    }

    private static boolean fibonacciRetest(List<HtxSwapKline> candles, int last, Direction side, BigDecimal close, BigDecimal emaFast) {
        for (int index = last - 1; index >= Math.max(DONCHIAN_BARS, last - FIB_SOURCE_BARS); index--) {
            BigDecimal high = high(candles, index - DONCHIAN_BARS, index - 1);
            BigDecimal low = low(candles, index - DONCHIAN_BARS, index - 1);
            boolean breakout = side == Direction.LONG ? candles.get(index).getClose().compareTo(high) > 0 : candles.get(index).getClose().compareTo(low) < 0;
            if (!breakout) continue;
            BigDecimal impulseHigh = high(candles, index, last - 1);
            BigDecimal impulseLow = low(candles, index, last - 1);
            BigDecimal range = impulseHigh.subtract(impulseLow);
            if (!positive(range)) continue;
            BigDecimal lower = impulseHigh.subtract(range.multiply(new BigDecimal("0.618")));
            BigDecimal upper = impulseHigh.subtract(range.multiply(new BigDecimal("0.382")));
            boolean inZone = close.compareTo(lower) >= 0 && close.compareTo(upper) <= 0;
            return inZone && (side == Direction.LONG ? close.compareTo(emaFast) >= 0 : close.compareTo(emaFast) <= 0);
        }
        return false;
    }

    private static boolean emaVwapPullback(Direction side, TrendCandidateSignal signal, BigDecimal vwap) {
        if (!positive(vwap) || !positive(signal.atr()) || !positive(signal.emaFast())) return false;
        boolean aligned = side == Direction.LONG ? signal.closePrice().compareTo(signal.emaFast()) >= 0 && signal.closePrice().compareTo(vwap) >= 0
                : signal.closePrice().compareTo(signal.emaFast()) <= 0 && signal.closePrice().compareTo(vwap) <= 0;
        BigDecimal distance = signal.closePrice().subtract(signal.emaFast()).abs().min(signal.closePrice().subtract(vwap).abs());
        return aligned && distance.compareTo(signal.atr().multiply(HALF)) <= 0;
    }

    private static boolean momentumContinuation(Direction side, TrendCandidateSignal signal) {
        if (signal.momentumPct() == null || signal.adx() == null) return false;
        return side == Direction.LONG ? signal.momentumPct().compareTo(new BigDecimal("0.0015")) >= 0 && signal.adx().compareTo(new BigDecimal("16")) >= 0
                : signal.momentumPct().compareTo(new BigDecimal("-0.0015")) <= 0 && signal.adx().compareTo(new BigDecimal("16")) >= 0;
    }

    private static boolean squeeze(List<HtxSwapKline> candles, int last, BigDecimal atr) {
        if (!positive(atr)) return false;
        int start = last - VWAP_BARS + 1;
        BigDecimal mean = BigDecimal.ZERO;
        for (int i = start; i <= last; i++) mean = mean.add(candles.get(i).getClose());
        mean = mean.divide(BigDecimal.valueOf(VWAP_BARS), 18, RoundingMode.HALF_UP);
        BigDecimal variance = BigDecimal.ZERO;
        for (int i = start; i <= last; i++) variance = variance.add(candles.get(i).getClose().subtract(mean).pow(2));
        BigDecimal std = BigDecimal.valueOf(Math.sqrt(variance.divide(BigDecimal.valueOf(VWAP_BARS), 18, RoundingMode.HALF_UP).doubleValue()));
        return std.multiply(TWO).compareTo(atr.multiply(THREE)) < 0;
    }

    private static boolean structureAligned(List<HtxSwapKline> candles, int last, Direction side) {
        BigDecimal recentHigh = high(candles, last - 7, last);
        BigDecimal recentLow = low(candles, last - 7, last);
        BigDecimal priorHigh = high(candles, last - 15, last - 8);
        BigDecimal priorLow = low(candles, last - 15, last - 8);
        return side == Direction.LONG ? recentHigh.compareTo(priorHigh) > 0 && recentLow.compareTo(priorLow) > 0
                : recentHigh.compareTo(priorHigh) < 0 && recentLow.compareTo(priorLow) < 0;
    }

    private static BigDecimal vwap(List<HtxSwapKline> candles, int last, int bars) {
        BigDecimal weighted = BigDecimal.ZERO, volume = BigDecimal.ZERO;
        for (int i = last - bars + 1; i <= last; i++) {
            HtxSwapKline c = candles.get(i); BigDecimal v = volume(c);
            weighted = weighted.add(c.getHigh().add(c.getLow()).add(c.getClose()).divide(THREE, 18, RoundingMode.HALF_UP).multiply(v)); volume = volume.add(v);
        }
        return positive(volume) ? weighted.divide(volume, 18, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }
    private static BigDecimal volume(HtxSwapKline c) { return positive(c.getTradeTurnover()) ? c.getTradeTurnover() : positive(c.getAmount()) ? c.getAmount() : positive(c.getVol()) ? c.getVol() : BigDecimal.ZERO; }
    private static BigDecimal high(List<HtxSwapKline> c, int start, int end) { BigDecimal r = c.get(start).getHigh(); for (int i=start+1;i<=end;i++) r=r.max(c.get(i).getHigh()); return r; }
    private static BigDecimal low(List<HtxSwapKline> c, int start, int end) { BigDecimal r = c.get(start).getLow(); for (int i=start+1;i<=end;i++) r=r.min(c.get(i).getLow()); return r; }
    private static boolean valid(HtxSwapKline c) { return c != null && c.getId()!=null && positive(c.getHigh()) && positive(c.getLow()) && positive(c.getClose()); }
    private static boolean positive(BigDecimal v) { return v != null && v.signum()>0; }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    public record Coverage(boolean available, Direction direction, boolean donchian, boolean fibonacci, boolean emaVwap,
                           boolean momentum, boolean squeeze, boolean structure, String primaryRoute, BigDecimal vwap) {
        static Coverage unavailable() { return new Coverage(false, Direction.FLAT, false,false,false,false,false,false,"UNAVAILABLE",BigDecimal.ZERO); }
    }

    public record ForwardOutcome(String model, Direction direction, int horizonBars, BigDecimal entryPrice,
                                 BigDecimal futureClose, BigDecimal grossReturnRate,
                                 BigDecimal roundTripFeeRate, BigDecimal netReturnRate) {
    }
}
