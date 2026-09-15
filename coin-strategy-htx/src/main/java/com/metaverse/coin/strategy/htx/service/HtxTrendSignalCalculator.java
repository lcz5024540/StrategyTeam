package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure trend-following signal calculator: EMA trend filter + Donchian breakout + ATR/ADX gates. */
public class HtxTrendSignalCalculator {

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public TrendSignal evaluate(List<HtxSwapKline> rawCandles, HtxTrendProperties props) {
        return evaluate(rawCandles, rawCandles, props, Long.MAX_VALUE);
    }

    public TrendSignal evaluate(List<HtxSwapKline> rawEntryCandles, List<HtxSwapKline> rawTrendCandles,
                                HtxTrendProperties props, long nowEpochSeconds) {
        List<HtxSwapKline> entryCandles = normalizeCompleted(rawEntryCandles, props.getPeriod(),
                props.getCandleCloseGraceSeconds(), nowEpochSeconds);
        List<HtxSwapKline> trendCandles = normalizeCompleted(rawTrendCandles, trendPeriod(props),
                props.getCandleCloseGraceSeconds(), nowEpochSeconds);
        int last = entryCandles.size() - 1;
        int entryRequired = Math.max(props.getSlowEmaPeriod(), props.getBreakoutLookback())
                + Math.max(props.getAtrPeriod(), props.getAdxPeriod()) + 3;
        int trendRequired = Math.max(props.getTrendFastEmaPeriod(), props.getTrendSlowEmaPeriod()) + 3;
        if (entryCandles.size() < entryRequired) {
            return TrendSignal.skip("not enough entry candles: " + entryCandles.size());
        }
        if (trendCandles.size() < trendRequired) {
            return TrendSignal.skip("not enough trend candles: " + trendCandles.size());
        }
        PriceArrays entry = arrays(entryCandles);
        if (!entry.valid()) {
            return TrendSignal.skip("entry kline contains non-positive OHLC");
        }
        PriceArrays trend = arrays(trendCandles);
        if (!trend.valid()) {
            return TrendSignal.skip("trend kline contains non-positive OHLC");
        }

        BigDecimal close = entry.closes()[last];
        BigDecimal emaFast = ema(entry.closes(), props.getFastEmaPeriod());
        BigDecimal emaSlow = ema(entry.closes(), props.getSlowEmaPeriod());
        BigDecimal trendEmaFast = ema(trend.closes(), props.getTrendFastEmaPeriod());
        BigDecimal trendEmaSlow = ema(trend.closes(), props.getTrendSlowEmaPeriod());
        BigDecimal breakoutHigh = highest(entry.highs(), last - props.getBreakoutLookback(), last - 1);
        BigDecimal breakoutLow = lowest(entry.lows(), last - props.getBreakoutLookback(), last - 1);
        BigDecimal atr = atr(entryCandles, props.getAtrPeriod());
        BigDecimal atrPct = atr.divide(close, 18, RoundingMode.HALF_UP);
        BigDecimal adx = adx(entryCandles, props.getAdxPeriod());
        TrendSide higherTrend = higherTrend(trendEmaFast, trendEmaSlow);

        if (atrPct.compareTo(props.getMinAtrPct()) < 0) {
            return TrendSignal.of(TrendSide.FLAT, entryCandles.get(last).getId(), close, emaFast, emaSlow,
                    breakoutHigh, breakoutLow, atr, atrPct, adx, "ATR below threshold");
        }
        if (adx.compareTo(props.getMinAdx()) < 0) {
            return TrendSignal.of(TrendSide.FLAT, entryCandles.get(last).getId(), close, emaFast, emaSlow,
                    breakoutHigh, breakoutLow, atr, atrPct, adx, "ADX below threshold");
        }
        if (higherTrend == TrendSide.LONG && close.compareTo(breakoutHigh) > 0 && emaFast.compareTo(emaSlow) > 0) {
            return TrendSignal.of(TrendSide.LONG, entryCandles.get(last).getId(), close, emaFast, emaSlow,
                    breakoutHigh, breakoutLow, atr, atrPct, adx, "close broke Donchian high with higher-timeframe EMA uptrend");
        }
        if (higherTrend == TrendSide.SHORT && close.compareTo(breakoutLow) < 0 && emaFast.compareTo(emaSlow) < 0) {
            return TrendSignal.of(TrendSide.SHORT, entryCandles.get(last).getId(), close, emaFast, emaSlow,
                    breakoutHigh, breakoutLow, atr, atrPct, adx, "close broke Donchian low with higher-timeframe EMA downtrend");
        }
        return TrendSignal.of(TrendSide.FLAT, entryCandles.get(last).getId(), close, emaFast, emaSlow,
                breakoutHigh, breakoutLow, atr, atrPct, adx, "no breakout aligned with higher-timeframe trend=" + higherTrend);
    }

    private static List<HtxSwapKline> normalizeCompleted(List<HtxSwapKline> rawCandles, String period,
                                                         long closeGraceSeconds, long nowEpochSeconds) {
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

    private static TrendSide higherTrend(BigDecimal trendEmaFast, BigDecimal trendEmaSlow) {
        if (trendEmaFast.compareTo(trendEmaSlow) > 0) {
            return TrendSide.LONG;
        }
        if (trendEmaFast.compareTo(trendEmaSlow) < 0) {
            return TrendSide.SHORT;
        }
        return TrendSide.FLAT;
    }

    private static String trendPeriod(HtxTrendProperties props) {
        return props.getTrendPeriod() == null || props.getTrendPeriod().trim().isEmpty()
                ? props.getPeriod() : props.getTrendPeriod();
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

    private static BigDecimal ema(BigDecimal[] values, int period) {
        BigDecimal multiplier = TWO.divide(BigDecimal.valueOf(period + 1L), 18, RoundingMode.HALF_UP);
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
            BigDecimal plusDi = sum(plusDm, start, end).multiply(HUNDRED).divide(tr, 18, RoundingMode.HALF_UP);
            BigDecimal minusDi = sum(minusDm, start, end).multiply(HUNDRED).divide(tr, 18, RoundingMode.HALF_UP);
            BigDecimal denom = plusDi.add(minusDi);
            if (denom.signum() <= 0) {
                dxValues.add(BigDecimal.ZERO);
            } else {
                dxValues.add(plusDi.subtract(minusDi).abs().multiply(HUNDRED).divide(denom, 18, RoundingMode.HALF_UP));
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
            BigDecimal range = high.subtract(low).abs();
            BigDecimal highClose = high.subtract(previousClose).abs();
            BigDecimal lowClose = low.subtract(previousClose).abs();
            result.add(range.max(highClose).max(lowClose));
        }
        return result;
    }

    private static BigDecimal averageTail(List<BigDecimal> values, int period) {
        int end = values.size() - 1;
        int start = Math.max(0, values.size() - period);
        return sum(values, start, end).divide(BigDecimal.valueOf(end - start + 1L), 18, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return sum(values, 0, values.size() - 1).divide(BigDecimal.valueOf(values.size()), 18, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> values, int start, int endInclusive) {
        BigDecimal result = BigDecimal.ZERO;
        for (int i = Math.max(0, start); i <= endInclusive && i < values.size(); i++) {
            result = result.add(values.get(i));
        }
        return result;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private record PriceArrays(BigDecimal[] closes, BigDecimal[] highs, BigDecimal[] lows, boolean valid) {
    }

    public enum TrendSide {
        LONG, SHORT, FLAT
    }

    public record TrendSignal(TrendSide side, Long barTime, BigDecimal closePrice, BigDecimal emaFast,
                              BigDecimal emaSlow, BigDecimal breakoutHigh, BigDecimal breakoutLow,
                              BigDecimal atr, BigDecimal atrPct, BigDecimal adx, String reason) {
        static TrendSignal skip(String reason) {
            return of(TrendSide.FLAT, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, reason);
        }

        static TrendSignal of(TrendSide side, Long barTime, BigDecimal closePrice, BigDecimal emaFast,
                              BigDecimal emaSlow, BigDecimal breakoutHigh, BigDecimal breakoutLow,
                              BigDecimal atr, BigDecimal atrPct, BigDecimal adx, String reason) {
            return new TrendSignal(side, barTime, closePrice, emaFast, emaSlow, breakoutHigh, breakoutLow,
                    atr, atrPct, adx, reason);
        }
    }
}
