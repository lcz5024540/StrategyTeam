package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 实时观测数据：当天已实测的温度极值 + 剩余小时逐时预报。
 * 用于 leadHours < 12 的超近期市场，提供接近确定性的 fair value。
 */
@Data
@Builder
public class WeatherRealtimeObservation {
    private String city;
    /** 当前实测温度 (°F) */
    private BigDecimal currentTempF;
    /** 今天到目前为止观测到的最高温 (°F) */
    private BigDecimal todayObservedMaxF;
    /** 今天到目前为止观测到的最低温 (°F) */
    private BigDecimal todayObservedMinF;
    /** 今天剩余小时的逐时预报温度 (°F) */
    private List<BigDecimal> remainingHourForecastsF;
    /** 观测时间 */
    private Instant observedAt;
    /** 数据源 */
    private String source;
}
