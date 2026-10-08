package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Computes additional trend evidence from the completed K-line window.
 *
 * <p>This calculator is deliberately read-only. Its output is persisted in the asynchronous
 * factor snapshot lane and must not be read by candidate selection, sizing, order placement, or
 * position management until an ablation replay authorizes a specific use.</p>
 */
public final class HtxTrendIndicatorObservationCalculator {

    private static final int RSI_PERIOD = 14;
    private static final int MACD_FAST_PERIOD = 12;
    private static final int MACD_SLOW_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;
    private static final int PARTICIPATION_LOOKBACK = 20;
    private static final int PULLBACK_WINDOW = 3;
    private static final int SCALE = 18;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public Observation observe(List<HtxSwapKline> rawCandles, Long completedBarTime) {
        List<HtxSwapKline> candles = completedCandles(rawCandles, completedBarTime);
        if (candles.size() < MACD_SLOW_PERIOD + MACD_SIGNAL_PERIOD) {
            return Observation.unavailable(candles.size(), "need " + (MACD_SLOW_PERIOD + MACD_SIGNAL_PERIOD)
                    + " completed candles for MACD");
        }
        List<BigDecimal> closes = candles.stream().map(HtxSwapKline::getClose).toList();
        int last = candles.size() - 1;
        BigDecimal close = closes.get(last);
        return new Observation("AVAILABLE", null, candles.size(), candles.get(last).getId(), close,
                rsi(closes), macd(closes), participation(candles), priceAction(candles.get(last)));
    }

    private static List<HtxSwapKline> completedCandles(List<HtxSwapKline> rawCandles, Long completedBarTime) {
        if (rawCandles == null || rawCandles.isEmpty()) {
            return List.of();
        }
        long latestCompleted = completedBarTime == null ? Long.MAX_VALUE : completedBarTime;
        return rawCandles.stream().filter(HtxTrendIndicatorObservationCalculator::validOhlc)
                .filter(candle -> candle.getId() <= latestCompleted)
                .sorted(Comparator.comparing(HtxSwapKline::getId)).toList();
    }

    private static boolean validOhlc(HtxSwapKline candle) {
        return candle != null && candle.getId() != null && positive(candle.getOpen()) && positive(candle.getHigh())
                && positive(candle.getLow()) && positive(candle.getClose());
    }

    private static RsiObservation rsi(List<BigDecimal> closes) {
        if (closes.size() <= RSI_PERIOD) {
            return RsiObservation.unavailable("need " + (RSI_PERIOD + 1) + " closes");
        }
        BigDecimal averageGain = BigDecimal.ZERO;
        BigDecimal averageLoss = BigDecimal.ZERO;
        for (int index = 1; index <= RSI_PERIOD; index++) {
            BigDecimal delta = closes.get(index).subtract(closes.get(index - 1));
            if (delta.signum() >= 0) averageGain = averageGain.add(delta);
            else averageLoss = averageLoss.add(delta.abs());
        }
        averageGain = divide(averageGain, BigDecimal.valueOf(RSI_PERIOD));
        averageLoss = divide(averageLoss, BigDecimal.valueOf(RSI_PERIOD));
        for (int index = RSI_PERIOD + 1; index < closes.size(); index++) {
            BigDecimal delta = closes.get(index).subtract(closes.get(index - 1));
            BigDecimal gain = delta.max(BigDecimal.ZERO);
            BigDecimal loss = delta.min(BigDecimal.ZERO).abs();
            averageGain = averageGain.multiply(BigDecimal.valueOf(RSI_PERIOD - 1L)).add(gain)
                    .divide(BigDecimal.valueOf(RSI_PERIOD), SCALE, RoundingMode.HALF_UP);
            averageLoss = averageLoss.multiply(BigDecimal.valueOf(RSI_PERIOD - 1L)).add(loss)
                    .divide(BigDecimal.valueOf(RSI_PERIOD), SCALE, RoundingMode.HALF_UP);
        }
        if (averageLoss.signum() == 0) {
            return new RsiObservation("AVAILABLE", new BigDecimal("100"), "NO_LOSSES", null);
        }
        BigDecimal value = HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(divide(averageGain, averageLoss)),
                SCALE, RoundingMode.HALF_UP));
        String zone = value.compareTo(new BigDecimal("70")) >= 0 ? "STRONG_UP"
                : value.compareTo(new BigDecimal("55")) >= 0 ? "UP"
                : value.compareTo(new BigDecimal("45")) > 0 ? "NEUTRAL"
                : value.compareTo(new BigDecimal("30")) > 0 ? "DOWN" : "STRONG_DOWN";
        return new RsiObservation("AVAILABLE", value, zone, null);
    }

    private static MacdObservation macd(List<BigDecimal> closes) {
        if (closes.size() < MACD_SLOW_PERIOD + MACD_SIGNAL_PERIOD) {
            return MacdObservation.unavailable("not enough closes");
        }
        List<BigDecimal> fast = emaSeries(closes, MACD_FAST_PERIOD);
        List<BigDecimal> slow = emaSeries(closes, MACD_SLOW_PERIOD);
        List<BigDecimal> line = new ArrayList<>(closes.size());
        for (int index = 0; index < closes.size(); index++) {
            line.add(fast.get(index).subtract(slow.get(index)));
        }
        List<BigDecimal> signal = emaSeries(line, MACD_SIGNAL_PERIOD);
        int last = line.size() - 1;
        BigDecimal histogram = line.get(last).subtract(signal.get(last));
        BigDecimal priorHistogram = line.get(last - 1).subtract(signal.get(last - 1));
        BigDecimal histogramSlope = histogram.subtract(priorHistogram);
        String acceleration = histogramSlope.signum() > 0 ? "EXPANDING_UP"
                : histogramSlope.signum() < 0 ? "EXPANDING_DOWN" : "FLAT";
        return new MacdObservation("AVAILABLE", line.get(last), signal.get(last), histogram, histogramSlope,
                acceleration, null);
    }

    private static List<BigDecimal> emaSeries(List<BigDecimal> values, int period) {
        BigDecimal multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> result = new ArrayList<>(values.size());
        BigDecimal previous = values.get(0);
        result.add(previous);
        for (int index = 1; index < values.size(); index++) {
            previous = values.get(index).subtract(previous).multiply(multiplier).add(previous);
            result.add(previous);
        }
        return result;
    }

    private static ParticipationObservation participation(List<HtxSwapKline> candles) {
        int last = candles.size() - 1;
        int start = Math.max(0, last - PARTICIPATION_LOOKBACK);
        List<BigDecimal> historicalVolumes = new ArrayList<>(last - start);
        List<BigDecimal> historicalTurnovers = new ArrayList<>(last - start);
        for (int index = start; index < last; index++) {
            BigDecimal volume = volumeOf(candles.get(index));
            BigDecimal turnover = turnoverOf(candles.get(index));
            if (positive(volume)) historicalVolumes.add(volume);
            if (positive(turnover)) historicalTurnovers.add(turnover);
        }
        BigDecimal volume = volumeOf(candles.get(last));
        BigDecimal turnover = turnoverOf(candles.get(last));
        BigDecimal volumeRatio = ratioToMean(volume, historicalVolumes);
        BigDecimal turnoverRatio = ratioToMean(turnover, historicalTurnovers);
        BigDecimal pullbackRatio = pullbackVolumeRatio(candles);
        BigDecimal returnRate = last == 0 ? BigDecimal.ZERO
                : divide(candles.get(last).getClose().subtract(candles.get(last - 1).getClose()), candles.get(last - 1).getClose());
        BigDecimal participationRatio = positive(volumeRatio) ? volumeRatio : turnoverRatio;
        if (!positive(participationRatio)) {
            return new ParticipationObservation("UNAVAILABLE", null, null, pullbackRatio, null, null,
                    "completed K-lines do not contain usable volume or turnover");
        }
        boolean volumeEvidenceAvailable = positive(volumeRatio);
        String evidenceSuffix = volumeEvidenceAvailable ? "" : "_BY_TURNOVER";
        String confirmation = returnRate.signum() > 0 && participationRatio.compareTo(BigDecimal.ONE) >= 0
                ? "UP_MOVE_CONFIRMED" + evidenceSuffix
                : returnRate.signum() < 0 && participationRatio.compareTo(BigDecimal.ONE) >= 0
                ? "DOWN_MOVE_CONFIRMED" + evidenceSuffix
                : participationRatio.compareTo(new BigDecimal("0.80")) < 0
                ? "LOW_PARTICIPATION" + evidenceSuffix : "NEUTRAL";
        String divergence = returnRate.signum() > 0 && participationRatio.compareTo(new BigDecimal("0.80")) < 0
                ? "UP_WITHOUT_VOLUME" + evidenceSuffix
                : returnRate.signum() < 0 && participationRatio.compareTo(new BigDecimal("0.80")) < 0
                ? "DOWN_WITHOUT_VOLUME" + evidenceSuffix : "NONE";
        return new ParticipationObservation(volumeEvidenceAvailable ? "AVAILABLE" : "PARTIAL", volumeRatio,
                turnoverRatio, pullbackRatio, confirmation, divergence,
                volumeEvidenceAvailable ? null : "volume unavailable; confirmation uses turnover ratio");
    }

    private static PriceActionObservation priceAction(HtxSwapKline candle) {
        BigDecimal range = candle.getHigh().subtract(candle.getLow());
        if (range.signum() <= 0) return PriceActionObservation.unavailable("zero candle range");
        BigDecimal closeLocation = divide(candle.getClose().subtract(candle.getLow()), range);
        BigDecimal bodyRatio = divide(candle.getClose().subtract(candle.getOpen()).abs(), range);
        BigDecimal upperWickRatio = divide(candle.getHigh().subtract(candle.getOpen().max(candle.getClose())), range);
        BigDecimal lowerWickRatio = divide(candle.getOpen().min(candle.getClose()).subtract(candle.getLow()), range);
        return new PriceActionObservation("AVAILABLE", closeLocation, bodyRatio, upperWickRatio, lowerWickRatio, null);
    }

    private static BigDecimal pullbackVolumeRatio(List<HtxSwapKline> candles) {
        int last = candles.size() - 1;
        if (last < PULLBACK_WINDOW * 2) return null;
        BigDecimal recent = meanVolumes(candles, last - PULLBACK_WINDOW + 1, last + 1);
        BigDecimal prior = meanVolumes(candles, last - PULLBACK_WINDOW * 2 + 1, last - PULLBACK_WINDOW + 1);
        return positive(prior) ? divide(recent, prior) : null;
    }

    private static BigDecimal meanVolumes(List<HtxSwapKline> candles, int from, int to) {
        List<BigDecimal> values = new ArrayList<>();
        for (int index = Math.max(0, from); index < Math.min(candles.size(), to); index++) {
            BigDecimal value = volumeOf(candles.get(index));
            if (positive(value)) values.add(value);
        }
        return mean(values);
    }

    private static BigDecimal volumeOf(HtxSwapKline candle) {
        return positive(candle.getVol()) ? candle.getVol() : candle.getAmount();
    }

    private static BigDecimal turnoverOf(HtxSwapKline candle) {
        return positive(candle.getTradeTurnover()) ? candle.getTradeTurnover() : candle.getAmount();
    }

    private static BigDecimal ratioToMean(BigDecimal value, List<BigDecimal> values) {
        BigDecimal average = mean(values);
        return positive(value) && positive(average) ? divide(value, average) : null;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (BigDecimal value : values) {
            if (positive(value)) {
                total = total.add(value);
                count++;
            }
        }
        return count == 0 ? null : total.divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return null;
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP);
    }

    public record Observation(String status, String reason, int completedBars, Long barTime, BigDecimal close,
                              RsiObservation rsi, MacdObservation macd, ParticipationObservation participation,
                              PriceActionObservation priceAction) {
        static Observation unavailable(int completedBars, String reason) {
            return new Observation("UNAVAILABLE", reason, completedBars, null, null,
                    RsiObservation.unavailable(reason), MacdObservation.unavailable(reason),
                    ParticipationObservation.unavailable(reason), PriceActionObservation.unavailable(reason));
        }
    }

    public record RsiObservation(String status, BigDecimal value, String zone, String reason) {
        static RsiObservation unavailable(String reason) { return new RsiObservation("UNAVAILABLE", null, null, reason); }
    }

    public record MacdObservation(String status, BigDecimal line, BigDecimal signal, BigDecimal histogram,
                                  BigDecimal histogramSlope, String acceleration, String reason) {
        static MacdObservation unavailable(String reason) {
            return new MacdObservation("UNAVAILABLE", null, null, null, null, null, reason);
        }
    }

    public record ParticipationObservation(String status, BigDecimal volumeRatio, BigDecimal turnoverRatio,
                                           BigDecimal recentToPriorVolumeRatio, String confirmation,
                                           String divergence, String reason) {
        static ParticipationObservation unavailable(String reason) {
            return new ParticipationObservation("UNAVAILABLE", null, null, null, null, null, reason);
        }
    }

    public record PriceActionObservation(String status, BigDecimal closeLocation, BigDecimal bodyRatio,
                                         BigDecimal upperWickRatio, BigDecimal lowerWickRatio, String reason) {
        static PriceActionObservation unavailable(String reason) {
            return new PriceActionObservation("UNAVAILABLE", null, null, null, null, reason);
        }
    }
}
