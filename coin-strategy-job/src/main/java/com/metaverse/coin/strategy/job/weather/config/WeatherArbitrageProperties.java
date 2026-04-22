package com.metaverse.coin.strategy.job.weather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "weather-arbitrage")
public class WeatherArbitrageProperties {

    private boolean enabled = false;
    private BigDecimal capitalPoolUsdc = new BigDecimal("500");
    private BigDecimal maxSingleStakeUsdc = new BigDecimal("50");
    private int maxOpenPositions = 5;
    private BigDecimal entryEdgeThreshold = new BigDecimal("0.10");
    private BigDecimal minLiquidityUsdc = new BigDecimal("50");
    /** Min hours until market resolution before we refuse to open new positions */
    private int minHoursToSettlement = 12;
    /** Max hours until market resolution — forecasts beyond this are unreliable */
    private int maxHoursToSettlement = 72;
    private BigDecimal sigmaF = new BigDecimal("3.5");
    private List<String> cities = List.of("London", "New York", "Seoul");
    private String tomorrowIoApiKey = "";
    private long scanFixedDelayMs = 900_000L;   // 15 min
    private long syncFixedDelayMs  = 300_000L;  // 5 min
}
