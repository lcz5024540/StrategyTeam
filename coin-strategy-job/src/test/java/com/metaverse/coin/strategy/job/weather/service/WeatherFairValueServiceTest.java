package com.metaverse.coin.strategy.job.weather.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.WeatherCalibrationProfileEntity;
import com.metaverse.coin.strategy.job.weather.config.WeatherArbitrageProperties;
import com.metaverse.coin.strategy.job.weather.model.WeatherFairValueResult;
import com.metaverse.coin.strategy.job.weather.model.WeatherForecast;
import com.metaverse.coin.strategy.job.weather.model.WeatherMarketSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WeatherFairValueServiceTest {

    private final WeatherArbitrageProperties properties = new WeatherArbitrageProperties();
    private final WeatherFairValueService service = new WeatherFairValueService(properties);

    @Test
    void computeFairValue_withCalibration_returnsActionableResult() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("m1")
                .city("London")
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("68"))
                .yesPrice(new BigDecimal("0.10"))
                .noPrice(new BigDecimal("0.90"))
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastDate(LocalDate.now())
                .targetDate(LocalDate.now().plusDays(1))
                .forecastTempMaxF(new BigDecimal("72"))
                .forecastTempMinF(new BigDecimal("55"))
                .forecastTempAvgF(new BigDecimal("63"))
                .leadHours(24)
                .dataSourceCount(1)
                .fetchedAt(Instant.now())
                .build();
        WeatherCalibrationProfileEntity profile = new WeatherCalibrationProfileEntity()
                .setCity("London")
                .setMonth(LocalDate.now().plusDays(1).getMonthValue())
                .setLeadHourBucket(24)
                .setSigmaMaxF(new BigDecimal("3.0"))
                .setSigmaMinF(new BigDecimal("2.5"))
                .setSampleSize(40)
                .setVersion("v1");

        WeatherFairValueResult result = service.computeFairValue(spec, forecast, Optional.of(profile));

        assertTrue(result.isActionable());
        assertTrue(result.getFairValue().compareTo(new BigDecimal("0.50")) > 0);
        assertTrue(result.getConfidence().compareTo(BigDecimal.ZERO) > 0);
        assertEquals("v1", result.getCalibrationVersion());
    }

    @Test
    void computeFairValue_withoutCalibration_returnsPass() {
        WeatherMarketSpec spec = WeatherMarketSpec.builder()
                .marketId("m1")
                .city("London")
                .temperatureType(WeatherMarketSpec.TemperatureType.MAX)
                .thresholdF(new BigDecimal("68"))
                .build();
        WeatherForecast forecast = WeatherForecast.builder()
                .city("London")
                .forecastDate(LocalDate.now())
                .targetDate(LocalDate.now().plusDays(1))
                .forecastTempMaxF(new BigDecimal("72"))
                .forecastTempMinF(new BigDecimal("55"))
                .forecastTempAvgF(new BigDecimal("63"))
                .leadHours(24)
                .dataSourceCount(1)
                .fetchedAt(Instant.now())
                .build();

        WeatherFairValueResult result = service.computeFairValue(spec, forecast, Optional.empty());

        assertFalse(result.isActionable());
        assertEquals("insufficient_calibration", result.getReason());
    }
}
