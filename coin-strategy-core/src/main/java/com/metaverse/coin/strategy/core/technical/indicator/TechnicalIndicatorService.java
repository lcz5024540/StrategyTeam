package com.metaverse.coin.strategy.core.technical.indicator;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import java.util.HashMap;
import java.util.Map;

/**
 * Service to calculate technical indicators using ta4j.
 */
public class TechnicalIndicatorService {

    /**
     * Calculate MACD (12, 26, 9)
     * @param series BarSeries
     * @return Map containing MACD, Signal, and Hist values (last bar)
     */
    public Map<String, Double> calculateMACD(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);

        int endIndex = series.getEndIndex();
        double macdValue = macd.getValue(endIndex).doubleValue();
        double signalValue = macdSignal.getValue(endIndex).doubleValue();
        double histValue = macdValue - signalValue;

        Map<String, Double> result = new HashMap<>();
        result.put("MACD", macdValue);
        result.put("Signal", signalValue);
        result.put("Hist", histValue);
        return result;
    }

    /**
     * Calculate EMA (Exponential Moving Average)
     * @param series BarSeries
     * @param period Period (e.g. 14, 50, 200)
     * @return EMA value (last bar)
     */
    public double calculateEMA(BarSeries series, int period) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator ema = new EMAIndicator(closePrice, period);
        return ema.getValue(series.getEndIndex()).doubleValue();
    }

    /**
     * Calculate RSI (Relative Strength Index)
     * @param series BarSeries
     * @param period Period (usually 14)
     * @return RSI value (last bar)
     */
    public double calculateRSI(BarSeries series, int period) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, period);
        return rsi.getValue(series.getEndIndex()).doubleValue();
    }

    /**
     * Calculate Volume (Simple Moving Average of Volume could be added later)
     * @param series BarSeries
     * @return Volume of the last bar
     */
    public double getCurrentVolume(BarSeries series) {
        VolumeIndicator volume = new VolumeIndicator(series);
        return volume.getValue(series.getEndIndex()).doubleValue();
    }
}
