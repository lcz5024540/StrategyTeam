package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 集成预报数据：包含多个模型的多个成员预测值。
 * 用于构建经验概率分布，取代单点正态假设。
 */
@Data
@Builder
public class WeatherEnsembleForecast {
    private String city;
    private LocalDate targetDate;
    /** 所有集成成员的 max temp (°F) */
    private List<BigDecimal> maxTempMembersF;
    /** 所有集成成员的 min temp (°F) */
    private List<BigDecimal> minTempMembersF;
    /** 成员总数 */
    private int totalMembers;
    /** 数据源模型数量 */
    private int modelCount;
    /** 拉取时间 */
    private Instant fetchedAt;
    /** 预报提前时间（小时） */
    private int leadHours;
    /** 各模型成员数量 {"GFS":31, "ECMWF":51, ...} */
    private Map<String, Integer> memberCountByModel;
}
