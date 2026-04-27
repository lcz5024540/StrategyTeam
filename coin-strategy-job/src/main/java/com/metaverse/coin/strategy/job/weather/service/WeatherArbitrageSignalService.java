package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherArbitrageSignal;
import com.metaverse.coin.strategy.job.weather.model.WeatherFairValueResult;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class WeatherArbitrageSignalService {

    private final WeatherArbitrageProperties properties;

    public WeatherArbitrageSignal evaluate(WeatherMarketSpec spec, WeatherFairValueResult fairValueResult) {
        if (spec.getParseConfidence() != null && spec.getParseConfidence().compareTo(properties.getConfidenceFloor()) < 0) {
            return pass(spec, "low_parse_confidence", fairValueResult);
        }
        if (!fairValueResult.isActionable()) {
            return pass(spec, fairValueResult.getReason(), fairValueResult);
        }
        if (spec.getLiquidityUsdc() == null || spec.getLiquidityUsdc().compareTo(properties.getMinLiquidityUsdc()) < 0) {
            return pass(spec, "insufficient_liquidity", fairValueResult);
        }
        if (fairValueResult.getConfidence().compareTo(properties.getConfidenceFloor()) < 0) {
            return pass(spec, "low_confidence", fairValueResult);
        }

        BigDecimal fairValue = fairValueResult.getFairValue();
        BigDecimal yesEdge = edge(fairValue, spec.getYesPrice());
        BigDecimal noFairValue = BigDecimal.ONE.subtract(fairValue);
        BigDecimal noEdge = edge(noFairValue, spec.getNoPrice());
        BigDecimal bestEdge = yesEdge.max(noEdge);
        BigDecimal expectedValue = fairValue.multiply(spec.getYesPrice() == null ? BigDecimal.ZERO : BigDecimal.ONE.subtract(spec.getYesPrice()))
                .subtract(BigDecimal.ONE.subtract(fairValue).multiply(spec.getYesPrice() == null ? BigDecimal.ZERO : spec.getYesPrice()))
                .setScale(8, RoundingMode.HALF_UP);

        if (bestEdge.compareTo(properties.getEntryEdgeThreshold()) < 0) {
            return pass(spec, "edge_below_threshold", fairValueResult);
        }
        if (expectedValue.compareTo(properties.getExpectedValueFloor()) < 0) {
            return pass(spec, "expected_value_below_floor", fairValueResult);
        }

        WeatherArbitrageSignal.Side side = yesEdge.compareTo(noEdge) >= 0
                ? WeatherArbitrageSignal.Side.BUY_YES
                : WeatherArbitrageSignal.Side.BUY_NO;
        BigDecimal marketPrice = side == WeatherArbitrageSignal.Side.BUY_YES ? spec.getYesPrice() : spec.getNoPrice();
        return WeatherArbitrageSignal.builder()
                .marketId(spec.getMarketId())
                .tokenId(spec.getTokenId())
                .side(side)
                .city(spec.getCity())
                .question(spec.getQuestion())
                .reason("signal_ok")
                .fairValue(side == WeatherArbitrageSignal.Side.BUY_YES ? fairValue : noFairValue)
                .marketPrice(marketPrice)
                .edge(side == WeatherArbitrageSignal.Side.BUY_YES ? yesEdge : noEdge)
                .expectedValue(expectedValue)
                .confidence(fairValueResult.getConfidence())
                .forecastAgeMinutes(fairValueResult.getForecastAgeMinutes())
                .leadHours(fairValueResult.getLeadHours())
                .calibrationVersion(fairValueResult.getCalibrationVersion())
                .mode(properties.getMode().name())
                .stakeUsdc(BigDecimal.ZERO)
                .build();
    }

    private WeatherArbitrageSignal pass(WeatherMarketSpec spec, String reason, WeatherFairValueResult result) {
        return WeatherArbitrageSignal.builder()
                .marketId(spec.getMarketId())
                .tokenId(spec.getTokenId())
                .side(WeatherArbitrageSignal.Side.PASS)
                .city(spec.getCity())
                .question(spec.getQuestion())
                .reason(reason)
                .fairValue(result.getFairValue())
                .marketPrice(spec.getYesPrice())
                .edge(BigDecimal.ZERO)
                .expectedValue(BigDecimal.ZERO)
                .confidence(result.getConfidence())
                .forecastAgeMinutes(result.getForecastAgeMinutes())
                .leadHours(result.getLeadHours())
                .calibrationVersion(result.getCalibrationVersion())
                .mode(properties.getMode().name())
                .stakeUsdc(BigDecimal.ZERO)
                .build();
    }

    private BigDecimal edge(BigDecimal fairValue, BigDecimal marketPrice) {
        if (fairValue == null || marketPrice == null || marketPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return fairValue.subtract(marketPrice)
                .divide(marketPrice, 8, RoundingMode.HALF_UP);
    }
}
