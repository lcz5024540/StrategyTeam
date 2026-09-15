package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendEnsembleShadowProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/** Multi-model crypto trend candidate calculator for shadow observation. */
public class HtxTrendEnsembleSignalCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal EMA_STRUCTURE_WEIGHT = new BigDecimal("30");
    private static final BigDecimal MOMENTUM_WEIGHT = new BigDecimal("20");
    private static final BigDecimal BREAKOUT_WEIGHT = new BigDecimal("20");
    private static final BigDecimal ADX_WEIGHT = new BigDecimal("10");
    private static final BigDecimal VOLUME_WEIGHT = new BigDecimal("10");
    private static final BigDecimal VOL_EXPANSION_WEIGHT = new BigDecimal("10");
    private static final int SCALE = 18;

    public TrendCandidateSignal evaluate(List<HtxSwapKline> rawCandles,
                                         HtxTrendEnsembleShadowProperties props,
                                         String period,
                                         long nowEpochSeconds) {
        List<HtxSwapKline> candles = normalizeCompleted(rawCandles, period,
                props.getCandleCloseGraceSeconds(), nowEpochSeconds);
        int required = max(props.getSlowEmaPeriod(), props.getBreakoutLookback(), props.getMomentumLookback(),
                props.getAtrPeriod(), props.getAdxPeriod(), props.getVolumeLookback()) + 5;
        if (candles.size() < required) {
            return TrendCandidateSignal.skip(period, "not enough candles: " + candles.size() + "/" + required);
        }
        PriceArrays prices = arrays(candles);
        if (!prices.valid()) {
            return TrendCandidateSignal.skip(period, "kline contains non-positive OHLC");
        }
        int last = candles.size() - 1;
        BigDecimal close = prices.closes()[last];
        BigDecimal emaFast = ema(prices.closes(), props.getFastEmaPeriod());
        BigDecimal emaMedium = ema(prices.closes(), props.getMediumEmaPeriod());
        BigDecimal emaSlow = ema(prices.closes(), props.getSlowEmaPeriod());
        BigDecimal breakoutHigh = highest(prices.highs(), last - props.getBreakoutLookback(), last - 1);
        BigDecimal breakoutLow = lowest(prices.lows(), last - props.getBreakoutLookback(), last - 1);
        BigDecimal atr = atr(candles, props.getAtrPeriod());
        BigDecimal atrPct = ratio(atr, close);
        BigDecimal adx = adx(candles, props.getAdxPeriod());
        BigDecimal momentumPct = momentum(prices.closes(), props.getMomentumLookback());
        BigDecimal volumeRatio = volumeRatio(candles, props.getVolumeLookback());

        Score score = score(close, emaFast, emaMedium, emaSlow, breakoutHigh, breakoutLow, atrPct, adx,
                momentumPct, volumeRatio, props);
        BigDecimal gap = score.longScore().subtract(score.shortScore()).abs();
        BigDecimal signalScore = score.longScore().max(score.shortScore());
        Direction direction = dominantDirection(score.longScore(), score.shortScore(), gap, signalScore, props);
        Regime regime = regime(close, emaFast, emaMedium, emaSlow, atrPct, adx, props);
        String invalidation = invalidationReason(direction, regime, gap, signalScore, atrPct, props);

        return new TrendCandidateSignal(period, candles.get(last).getId(), direction, regime, signalScore,
                score.longScore(), score.shortScore(), gap, close, emaFast, emaMedium, emaSlow, breakoutHigh,
                breakoutLow, atr, atrPct, adx, momentumPct, volumeRatio, score.reason(), invalidation);
    }

    private static Score score(BigDecimal close,
                               BigDecimal emaFast,
                               BigDecimal emaMedium,
                               BigDecimal emaSlow,
                               BigDecimal breakoutHigh,
                               BigDecimal breakoutLow,
                               BigDecimal atrPct,
                               BigDecimal adx,
                               BigDecimal momentumPct,
                               BigDecimal volumeRatio,
                               HtxTrendEnsembleShadowProperties props) {
        BigDecimal longScore = BigDecimal.ZERO;
        BigDecimal shortScore = BigDecimal.ZERO;
        StringJoiner reason = new StringJoiner("; ");

        if (close.compareTo(emaFast) > 0 && emaFast.compareTo(emaMedium) > 0 && emaMedium.compareTo(emaSlow) > 0) {
            longScore = longScore.add(EMA_STRUCTURE_WEIGHT);
            reason.add("EMA20/60/120 bullish stack");
        } else if (close.compareTo(emaFast) < 0 && emaFast.compareTo(emaMedium) < 0 && emaMedium.compareTo(emaSlow) < 0) {
            shortScore = shortScore.add(EMA_STRUCTURE_WEIGHT);
            reason.add("EMA20/60/120 bearish stack");
        } else if (close.compareTo(emaMedium) > 0 && emaFast.compareTo(emaSlow) > 0) {
            longScore = longScore.add(new BigDecimal("12"));
            reason.add("partial bullish EMA alignment");
        } else if (close.compareTo(emaMedium) < 0 && emaFast.compareTo(emaSlow) < 0) {
            shortScore = shortScore.add(new BigDecimal("12"));
            reason.add("partial bearish EMA alignment");
        }

        BigDecimal momentumAbs = momentumPct.abs();
        if (momentumPct.compareTo(props.getMinMomentumPct()) >= 0) {
            BigDecimal contribution = boundedContribution(momentumAbs, props.getMinMomentumPct(), MOMENTUM_WEIGHT);
            longScore = longScore.add(contribution);
            reason.add("positive momentum " + pct(momentumPct));
        } else if (momentumPct.compareTo(props.getMinMomentumPct().negate()) <= 0) {
            BigDecimal contribution = boundedContribution(momentumAbs, props.getMinMomentumPct(), MOMENTUM_WEIGHT);
            shortScore = shortScore.add(contribution);
            reason.add("negative momentum " + pct(momentumPct));
        }

        if (close.compareTo(breakoutHigh) > 0) {
            longScore = longScore.add(BREAKOUT_WEIGHT);
            reason.add("close above Donchian high");
        } else if (close.compareTo(breakoutLow) < 0) {
            shortScore = shortScore.add(BREAKOUT_WEIGHT);
            reason.add("close below Donchian low");
        }

        Direction bias = bias(longScore, shortScore, momentumPct);
        if (adx.compareTo(props.getMinAdx()) >= 0 && bias != Direction.FLAT) {
            if (bias == Direction.LONG) {
                longScore = longScore.add(ADX_WEIGHT);
            } else {
                shortScore = shortScore.add(ADX_WEIGHT);
            }
            reason.add("ADX confirms trend strength " + adx.setScale(2, RoundingMode.HALF_UP));
        }
        if (atrPct.compareTo(props.getMinAtrPct()) >= 0 && bias != Direction.FLAT) {
            if (bias == Direction.LONG) {
                longScore = longScore.add(VOL_EXPANSION_WEIGHT);
            } else {
                shortScore = shortScore.add(VOL_EXPANSION_WEIGHT);
            }
            reason.add("ATR volatility usable " + pct(atrPct));
        }
        if (volumeRatio.compareTo(props.getMinVolumeRatio()) >= 0 && bias != Direction.FLAT) {
            if (bias == Direction.LONG) {
                longScore = longScore.add(VOLUME_WEIGHT);
            } else {
                shortScore = shortScore.add(VOLUME_WEIGHT);
            }
            reason.add("volume expansion ratio=" + volumeRatio.setScale(2, RoundingMode.HALF_UP));
        }
        if (reason.length() == 0) {
            reason.add("no ensemble component reached activation threshold");
        }
        return new Score(clampScore(longScore), clampScore(shortScore), reason.toString());
    }

    private static Direction dominantDirection(BigDecimal longScore,
                                               BigDecimal shortScore,
                                               BigDecimal gap,
                                               BigDecimal signalScore,
                                               HtxTrendEnsembleShadowProperties props) {
        if (signalScore.compareTo(props.getEntryScoreThreshold()) < 0 || gap.compareTo(props.getMinScoreGap()) < 0) {
            return Direction.FLAT;
        }
        return longScore.compareTo(shortScore) > 0 ? Direction.LONG : Direction.SHORT;
    }

    private static String invalidationReason(Direction direction,
                                             Regime regime,
                                             BigDecimal gap,
                                             BigDecimal signalScore,
                                             BigDecimal atrPct,
                                             HtxTrendEnsembleShadowProperties props) {
        List<String> reasons = new ArrayList<>();
        if (direction == Direction.FLAT) {
            if (signalScore.compareTo(props.getEntryScoreThreshold()) < 0) {
                reasons.add("score below threshold " + props.getEntryScoreThreshold());
            }
            if (gap.compareTo(props.getMinScoreGap()) < 0) {
                reasons.add("long/short score gap too small " + gap.setScale(2, RoundingMode.HALF_UP));
            }
        }
        if (atrPct.compareTo(props.getMinAtrPct()) < 0) {
            reasons.add("ATR below tradable threshold " + pct(atrPct));
        }
        if (regime == Regime.RANGE) {
            reasons.add("range regime");
        }
        if (reasons.isEmpty()) {
            return "candidate valid for shadow observation";
        }
        return String.join("; ", reasons);
    }

    private static Regime regime(BigDecimal close,
                                 BigDecimal emaFast,
                                 BigDecimal emaMedium,
                                 BigDecimal emaSlow,
                                 BigDecimal atrPct,
                                 BigDecimal adx,
                                 HtxTrendEnsembleShadowProperties props) {
        if (atrPct.compareTo(props.getMinAtrPct()) < 0) {
            return Regime.LOW_VOLATILITY;
        }
        if (close.compareTo(emaFast) > 0 && emaFast.compareTo(emaMedium) > 0 && emaMedium.compareTo(emaSlow) > 0
                && adx.compareTo(props.getMinAdx()) >= 0) {
            return Regime.TRENDING_UP;
        }
        if (close.compareTo(emaFast) < 0 && emaFast.compareTo(emaMedium) < 0 && emaMedium.compareTo(emaSlow) < 0
                && adx.compareTo(props.getMinAdx()) >= 0) {
            return Regime.TRENDING_DOWN;
        }
        return Regime.RANGE;
    }

    private static Direction bias(BigDecimal longScore, BigDecimal shortScore, BigDecimal momentumPct) {
        int compare = longScore.compareTo(shortScore);
        if (compare > 0) {
            return Direction.LONG;
        }
        if (compare < 0) {
            return Direction.SHORT;
        }
        if (momentumPct.signum() > 0) {
            return Direction.LONG;
        }
        if (momentumPct.signum() < 0) {
            return Direction.SHORT;
        }
        return Direction.FLAT;
    }

    private static BigDecimal boundedContribution(BigDecimal actual, BigDecimal threshold, BigDecimal maxWeight) {
        if (threshold == null || threshold.signum() <= 0) {
            return maxWeight;
        }
        BigDecimal scaled = actual.divide(threshold, SCALE, RoundingMode.HALF_UP)
                .multiply(maxWeight.divide(TWO, SCALE, RoundingMode.HALF_UP));
        return scaled.min(maxWeight).setScale(6, RoundingMode.HALF_UP);
    }

    private static List<HtxSwapKline> normalizeCompleted(List<HtxSwapKline> rawCandles,
                                                         String period,
                                                         long closeGraceSeconds,
                                                         long nowEpochSeconds) {
        if (rawCandles == null || rawCandles.isEmpty()) {
            return List.of();
        }
        long seconds = periodSeconds(period);
        long grace = Math.max(0L, closeGraceSeconds);
        List<HtxSwapKline> candles = new ArrayList<>(rawCandles.stream()
                .filter(item -> item != null && item.getId() != null)
                .filter(item -> nowEpochSeconds == Long.MAX_VALUE || item.getId() + seconds + grace <= nowEpochSeconds)
                .toList());
        candles.sort(Comparator.comparing(HtxSwapKline::getId));
        return candles;
    }

    private static PriceArrays arrays(List<HtxSwapKline> candles) {
        BigDecimal[] closes = new BigDecimal[candles.size()];
        BigDecimal[] highs = new BigDecimal[candles.size()];
        BigDecimal[] lows = new BigDecimal[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            HtxSwapKline candle = candles.get(i);
            closes[i] = candle.getClose();
            highs[i] = candle.getHigh();
            lows[i] = candle.getLow();
            if (!positive(closes[i]) || !positive(highs[i]) || !positive(lows[i])) {
                return new PriceArrays(closes, highs, lows, false);
            }
        }
        return new PriceArrays(closes, highs, lows, true);
    }

    private static BigDecimal ema(BigDecimal[] values, int period) {
        BigDecimal multiplier = TWO.divide(BigDecimal.valueOf(period + 1L), SCALE, RoundingMode.HALF_UP);
        BigDecimal ema = values[0];
        for (int i = 1; i < values.length; i++) {
            ema = values[i].subtract(ema).multiply(multiplier).add(ema);
        }
        return ema;
    }

    private static BigDecimal highest(BigDecimal[] values, int start, int endInclusive) {
        BigDecimal result = values[Math.max(0, start)];
        for (int i = Math.max(0, start); i <= endInclusive; i++) {
            if (values[i].compareTo(result) > 0) {
                result = values[i];
            }
        }
        return result;
    }

    private static BigDecimal lowest(BigDecimal[] values, int start, int endInclusive) {
        BigDecimal result = values[Math.max(0, start)];
        for (int i = Math.max(0, start); i <= endInclusive; i++) {
            if (values[i].compareTo(result) < 0) {
                result = values[i];
            }
        }
        return result;
    }

    private static BigDecimal atr(List<HtxSwapKline> candles, int period) {
        List<BigDecimal> trueRanges = trueRanges(candles);
        return averageTail(trueRanges, period);
    }

    private static BigDecimal adx(List<HtxSwapKline> candles, int period) {
        List<BigDecimal> trueRanges = trueRanges(candles);
        List<BigDecimal> plusDm = new ArrayList<>();
        List<BigDecimal> minusDm = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            BigDecimal upMove = candles.get(i).getHigh().subtract(candles.get(i - 1).getHigh());
            BigDecimal downMove = candles.get(i - 1).getLow().subtract(candles.get(i).getLow());
            plusDm.add(upMove.compareTo(downMove) > 0 && upMove.signum() > 0 ? upMove : BigDecimal.ZERO);
            minusDm.add(downMove.compareTo(upMove) > 0 && downMove.signum() > 0 ? downMove : BigDecimal.ZERO);
        }
        int dxCount = Math.min(period, trueRanges.size());
        List<BigDecimal> dxValues = new ArrayList<>();
        for (int end = trueRanges.size() - dxCount; end < trueRanges.size(); end++) {
            int start = Math.max(0, end - period + 1);
            BigDecimal tr = sum(trueRanges, start, end);
            if (tr.signum() <= 0) {
                dxValues.add(BigDecimal.ZERO);
                continue;
            }
            BigDecimal plusDi = sum(plusDm, start, end).multiply(HUNDRED).divide(tr, SCALE, RoundingMode.HALF_UP);
            BigDecimal minusDi = sum(minusDm, start, end).multiply(HUNDRED).divide(tr, SCALE, RoundingMode.HALF_UP);
            BigDecimal denom = plusDi.add(minusDi);
            if (denom.signum() <= 0) {
                dxValues.add(BigDecimal.ZERO);
            } else {
                dxValues.add(plusDi.subtract(minusDi).abs().multiply(HUNDRED).divide(denom, SCALE, RoundingMode.HALF_UP));
            }
        }
        return average(dxValues);
    }

    private static List<BigDecimal> trueRanges(List<HtxSwapKline> candles) {
        List<BigDecimal> result = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            BigDecimal high = candles.get(i).getHigh();
            BigDecimal low = candles.get(i).getLow();
            BigDecimal previousClose = candles.get(i - 1).getClose();
            result.add(high.subtract(low).abs()
                    .max(high.subtract(previousClose).abs())
                    .max(low.subtract(previousClose).abs()));
        }
        return result;
    }

    private static BigDecimal momentum(BigDecimal[] closes, int lookback) {
        int start = closes.length - 1 - lookback;
        if (start < 0 || !positive(closes[start])) {
            return BigDecimal.ZERO;
        }
        return closes[closes.length - 1].subtract(closes[start]).divide(closes[start], SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal volumeRatio(List<HtxSwapKline> candles, int lookback) {
        int last = candles.size() - 1;
        BigDecimal current = volume(candles.get(last));
        List<BigDecimal> values = new ArrayList<>();
        for (int i = Math.max(0, last - lookback); i < last; i++) {
            BigDecimal volume = volume(candles.get(i));
            if (volume.signum() > 0) {
                values.add(volume);
            }
        }
        BigDecimal average = average(values);
        if (average.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return current.divide(average, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal volume(HtxSwapKline candle) {
        if (positive(candle.getAmount())) {
            return candle.getAmount();
        }
        if (positive(candle.getVol())) {
            return candle.getVol();
        }
        if (positive(candle.getTradeTurnover())) {
            return candle.getTradeTurnover();
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal averageTail(List<BigDecimal> values, int period) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int end = values.size() - 1;
        int start = Math.max(0, values.size() - period);
        return sum(values, start, end).divide(BigDecimal.valueOf(end - start + 1L), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return sum(values, 0, values.size() - 1).divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> values, int start, int endInclusive) {
        BigDecimal result = BigDecimal.ZERO;
        for (int i = Math.max(0, start); i <= endInclusive && i < values.size(); i++) {
            result = result.add(values.get(i));
        }
        return result;
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (!positive(numerator) || !positive(denominator)) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal clampScore(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(HUNDRED) > 0) {
            return HUNDRED;
        }
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static String pct(BigDecimal value) {
        return value.multiply(HUNDRED).setScale(3, RoundingMode.HALF_UP) + "%";
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static int max(int... values) {
        int result = 0;
        for (int value : values) {
            result = Math.max(result, value);
        }
        return result;
    }

    private static long periodSeconds(String period) {
        String value = period == null ? "" : period.trim().toLowerCase();
        if (value.endsWith("min")) {
            return Long.parseLong(value.substring(0, value.length() - 3)) * 60L;
        }
        if (value.endsWith("hour")) {
            return Long.parseLong(value.substring(0, value.length() - 4)) * 3_600L;
        }
        if (value.endsWith("day")) {
            return Long.parseLong(value.substring(0, value.length() - 3)) * 86_400L;
        }
        throw new IllegalArgumentException("unsupported HTX kline period: " + period);
    }

    public enum Direction {
        LONG, SHORT, FLAT
    }

    public enum Regime {
        TRENDING_UP, TRENDING_DOWN, RANGE, LOW_VOLATILITY
    }

    public record TrendCandidateSignal(
            String period,
            Long barTime,
            Direction direction,
            Regime regime,
            BigDecimal signalScore,
            BigDecimal longScore,
            BigDecimal shortScore,
            BigDecimal scoreGap,
            BigDecimal closePrice,
            BigDecimal emaFast,
            BigDecimal emaMedium,
            BigDecimal emaSlow,
            BigDecimal breakoutHigh,
            BigDecimal breakoutLow,
            BigDecimal atr,
            BigDecimal atrPct,
            BigDecimal adx,
            BigDecimal momentumPct,
            BigDecimal volumeRatio,
            String entryReason,
            String invalidationReason) {

        static TrendCandidateSignal skip(String period, String reason) {
            return new TrendCandidateSignal(period, null, Direction.FLAT, Regime.RANGE, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    "skip", reason);
        }
    }

    private record Score(BigDecimal longScore, BigDecimal shortScore, String reason) {
    }

    private record PriceArrays(BigDecimal[] closes, BigDecimal[] highs, BigDecimal[] lows, boolean valid) {
    }
}
