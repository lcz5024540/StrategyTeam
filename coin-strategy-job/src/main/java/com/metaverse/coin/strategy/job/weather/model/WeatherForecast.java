package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
public class WeatherForecast {
    private String city;
    private LocalDate forecastDate;
    private LocalDate targetDate;
    private BigDecimal forecastTempMaxF;
    private BigDecimal forecastTempMinF;
    private BigDecimal forecastTempAvgF;
    private String provider;
    private int leadHours;
    private int dataSourceCount;
    private Instant fetchedAt;
}
