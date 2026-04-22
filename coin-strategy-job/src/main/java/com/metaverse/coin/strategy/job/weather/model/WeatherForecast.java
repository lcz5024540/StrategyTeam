package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class WeatherForecast {
    private String city;
    private BigDecimal forecastTempMaxF;
    private BigDecimal forecastTempMinF;
    private BigDecimal forecastTempAvgF;
    private Instant fetchedAt;
}
