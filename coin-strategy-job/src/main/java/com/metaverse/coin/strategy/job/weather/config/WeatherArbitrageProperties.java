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

    // ── V2: 多频率调度 ──
    private long ultraNearScanMs = 120_000L;  // 超近期 0-6h: 2 min
    private long nearScanMs      = 300_000L;  // 近期 6-24h: 5 min
    private long mediumScanMs    = 900_000L;  // 中期 24-72h: 15 min
    private long farScanMs       = 1_800_000L; // 远期 72-168h: 30 min

    // ── V2: 分层阈值 ──
    private BigDecimal ultraNearMinEdge   = new BigDecimal("0.03");
    private BigDecimal nearMinEdge        = new BigDecimal("0.05");
    private BigDecimal mediumMinEdge      = new BigDecimal("0.08");
    private BigDecimal farMinEdge         = new BigDecimal("0.12");

    private BigDecimal ultraNearKellyFraction = new BigDecimal("0.40");
    private BigDecimal nearKellyFraction      = new BigDecimal("0.30");
    private BigDecimal mediumKellyFraction    = new BigDecimal("0.25");
    private BigDecimal farKellyFraction       = new BigDecimal("0.15");

    // ── V2: 多源共识 ──
    private BigDecimal minConsensusScore = new BigDecimal("0.40");

    // ── V2: 风控 ──
    private BigDecimal maxDailyDrawdownPct = new BigDecimal("0.10");
    private BigDecimal maxCityDateExposureRatio = new BigDecimal("0.25");
    private BigDecimal maxEnsembleStdDevF = new BigDecimal("8.0");

    private static List<CityRule> defaultCities() {
        List<CityRule> result = new ArrayList<>();
        result.add(new CityRule("New York", "America/New_York", 40.7128, -74.0060, List.of("new york", "nyc"), "KNYNEWYO722"));
        result.add(new CityRule("London", "Europe/London", 51.5074, -0.1278, List.of("london"), "IGREAT143"));
        result.add(new CityRule("Seoul", "Asia/Seoul", 37.5665, 126.9780, List.of("seoul"), "ISEOUL83"));
        result.add(new CityRule("Paris", "Europe/Paris", 48.8566, 2.3522, List.of("paris"), "IPARIS45"));
        result.add(new CityRule("Shanghai", "Asia/Shanghai", 31.2304, 121.4737, List.of("shanghai"), "ISHANG68"));
        result.add(new CityRule("Denver", "America/Denver", 39.7392, -104.9903, List.of("denver"), "KCODENVE445"));
        result.add(new CityRule("Seattle", "America/Los_Angeles", 47.6062, -122.3321, List.of("seattle"), "KWASEATT616"));
        result.add(new CityRule("Miami", "America/New_York", 25.7617, -80.1918, List.of("miami"), "KFLMIAMI294"));
        result.add(new CityRule("Tokyo", "Asia/Tokyo", 35.6762, 139.6503, List.of("tokyo"), "ITOKYO24"));
        result.add(new CityRule("Sydney", "Australia/Sydney", -33.8688, 151.2093, List.of("sydney"), "INSW1247"));
        return result;
    }

    @Data
    public static class CityRule {
        private String name;
        private String timeZone;
        private Double latitude;
        private Double longitude;
        private List<String> aliases = new ArrayList<>();
        /** Weather Underground 气象站编号 — Polymarket 用 WU 结算 */
        private String wundergroundStation;

        public CityRule() {
        }

        public CityRule(String name, String timeZone, List<String> aliases) {
            this.name = name;
            this.timeZone = timeZone;
            this.aliases = aliases;
        }

        public CityRule(String name, String timeZone, Double latitude, Double longitude,
                        List<String> aliases, String wundergroundStation) {
            this.name = name;
            this.timeZone = timeZone;
            this.latitude = latitude;
            this.longitude = longitude;
            this.aliases = aliases;
            this.wundergroundStation = wundergroundStation;
        }
    }
}
