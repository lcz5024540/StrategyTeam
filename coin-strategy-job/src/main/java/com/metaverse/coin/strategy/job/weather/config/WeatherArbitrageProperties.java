package com.metaverse.coin.strategy.job.weather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "weather-arbitrage")
public class WeatherArbitrageProperties {

    public enum Mode {
        OFF,
        SHADOW,
        PAPER,
        LIVE
    }

    private boolean enabled = false;
    private Mode mode = Mode.OFF;
    private BigDecimal capitalPoolUsdc = new BigDecimal("500");
    private BigDecimal maxSingleStakeUsdc = new BigDecimal("50");
    private int maxOpenPositions = 5;
    private BigDecimal entryEdgeThreshold = new BigDecimal("0.10");
    private BigDecimal expectedValueFloor = new BigDecimal("0.02");
    private BigDecimal minLiquidityUsdc = new BigDecimal("50");
    /** Min hours until market resolution before we refuse to open new positions */
    private int minHoursToSettlement = 12;
    /** Max hours until market resolution — forecasts beyond this are unreliable */
    private int maxHoursToSettlement = 72;
    private BigDecimal sigmaF = new BigDecimal("3.5"); // fallback only
    private int minCalibrationSampleSize = 20;
    private int maxForecastAgeMinutes = 180;
    private List<Integer> allowedLeadHourBuckets = List.of(12, 24, 48, 72);
    private int minDataSourceCount = 1;
    private BigDecimal confidenceFloor = new BigDecimal("0.55");
    private int maxMarketsPerScan = 150;
    private List<CityRule> cities = defaultCities();
    private String tomorrowIoApiKey = "";
    private long scanFixedDelayMs = 900_000L;   // 15 min
    private long syncFixedDelayMs  = 300_000L;  // 5 min

    private static List<CityRule> defaultCities() {
        List<CityRule> result = new ArrayList<>();
        result.add(new CityRule("London", "Europe/London", List.of("london")));
        result.add(new CityRule("New York", "America/New_York", List.of("new york", "nyc")));
        result.add(new CityRule("Seoul", "Asia/Seoul", List.of("seoul")));
        return result;
    }

    @Data
    public static class CityRule {
        private String name;
        private String timeZone;
        private List<String> aliases = new ArrayList<>();

        public CityRule() {
        }

        public CityRule(String name, String timeZone, List<String> aliases) {
            this.name = name;
            this.timeZone = timeZone;
            this.aliases = aliases;
        }
    }
}
