package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class WeatherFairValueResult {
    private boolean actionable;
    private BigDecimal fairValue;
    private BigDecimal confidence;
    private BigDecimal sigmaF;
    private String calibrationVersion;
    private int sampleSize;
    private int leadHours;
    private int forecastAgeMinutes;
    private String reason;
    private String explain;
}
