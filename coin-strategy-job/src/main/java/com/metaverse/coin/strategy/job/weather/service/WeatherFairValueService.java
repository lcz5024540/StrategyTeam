package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherCalibrationProfileEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherFairValueResult;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WeatherFairValueService {

    private final WeatherArbitrageProperties properties;

    public WeatherFairValueResult computeFairValue(WeatherMarketSpec spec,
                                                   WeatherForecast forecast,
                                                   Optional<WeatherCalibrationProfileEntity> calibrationProfile) {
        int forecastAgeMinutes = (int) Duration.between(forecast.getFetchedAt(), Instant.now()).toMinutes();
        if (forecastAgeMinutes > properties.getMaxForecastAgeMinutes()) {
            return pass("forecast_stale", forecastAgeMinutes, forecast.getLeadHours(), calibrationProfile);
        }
        if (forecast.getDataSourceCount() < properties.getMinDataSourceCount()) {
            return pass("insufficient_data_sources", forecastAgeMinutes, forecast.getLeadHours(), calibrationProfile);
        }
        if (calibrationProfile.isEmpty()) {
            return pass("insufficient_calibration", forecastAgeMinutes, forecast.getLeadHours(), Optional.empty());
        }
        WeatherCalibrationProfileEntity profile = calibrationProfile.get();
        if (profile.getSampleSize() == null || profile.getSampleSize() < properties.getMinCalibrationSampleSize()) {
            return pass("insufficient_calibration", forecastAgeMinutes, forecast.getLeadHours(), calibrationProfile);
        }

        BigDecimal sigma = switch (spec.getTemperatureType()) {
            case MAX -> firstPositive(profile.getSigmaMaxF(), properties.getSigmaF());
            case MIN -> firstPositive(profile.getSigmaMinF(), properties.getSigmaF());
            case AVG -> firstPositive(profile.getSigmaMaxF(), properties.getSigmaF());
        };
        BigDecimal anchorTemp = switch (spec.getTemperatureType()) {
            case MAX -> forecast.getForecastTempMaxF();
            case MIN -> forecast.getForecastTempMinF();
            case AVG -> forecast.getForecastTempAvgF();
        };
        double z = spec.getThresholdF().subtract(anchorTemp)
                .divide(sigma, 8, RoundingMode.HALF_UP)
                .doubleValue();
        double fairValue = switch (spec.getTemperatureType()) {
            case MAX -> 1.0d - normalCdf(z);
            case MIN -> normalCdf(z);
            case AVG -> 1.0d - normalCdf(z);
        };
        BigDecimal confidence = BigDecimal.valueOf(profile.getSampleSize())
                .divide(BigDecimal.valueOf(profile.getSampleSize() + 20L), 8, RoundingMode.HALF_UP)
                .max(new BigDecimal("0.01"))
                .min(BigDecimal.ONE);
        return WeatherFairValueResult.builder()
                .actionable(true)
                .fairValue(BigDecimal.valueOf(fairValue).setScale(8, RoundingMode.HALF_UP))
                .confidence(confidence)
                .sigmaF(sigma)
                .calibrationVersion(profile.getVersion())
                .sampleSize(profile.getSampleSize())
                .leadHours(forecast.getLeadHours())
                .forecastAgeMinutes(forecastAgeMinutes)
                .reason("ok")
                .explain("sigma=" + sigma + ", sampleSize=" + profile.getSampleSize())
                .build();
    }

    private WeatherFairValueResult pass(String reason,
                                        int forecastAgeMinutes,
                                        int leadHours,
                                        Optional<WeatherCalibrationProfileEntity> calibrationProfile) {
        return WeatherFairValueResult.builder()
                .actionable(false)
                .fairValue(BigDecimal.ZERO)
                .confidence(BigDecimal.ZERO)
                .sigmaF(calibrationProfile.map(WeatherCalibrationProfileEntity::getSigmaMaxF).orElse(properties.getSigmaF()))
                .calibrationVersion(calibrationProfile.map(WeatherCalibrationProfileEntity::getVersion).orElse(""))
                .sampleSize(calibrationProfile.map(WeatherCalibrationProfileEntity::getSampleSize).orElse(0))
                .leadHours(leadHours)
                .forecastAgeMinutes(forecastAgeMinutes)
                .reason(reason)
                .explain(reason)
                .build();
    }

    private double normalCdf(double x) {
        return 0.5d * (1.0d + erf(x / Math.sqrt(2.0d)));
    }

    private double erf(double x) {
        double sign = x < 0 ? -1.0d : 1.0d;
        x = Math.abs(x);
        double a1 = 0.254829592d;
        double a2 = -0.284496736d;
        double a3 = 1.421413741d;
        double a4 = -1.453152027d;
        double a5 = 1.061405429d;
        double p = 0.3275911d;
        double t = 1.0d / (1.0d + p * x);
        double y = 1.0d - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        return sign * y;
    }

    private BigDecimal firstPositive(BigDecimal preferred, BigDecimal fallback) {
        if (preferred != null && preferred.compareTo(BigDecimal.ZERO) > 0) {
            return preferred;
        }
        return fallback;
    }
}
