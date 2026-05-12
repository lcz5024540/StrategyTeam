package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 多模型确定性预报：多个官方气象模型各自的确定性预测值。
 * 用于多源交叉验证 — 各国气象局独立运行的模型如果预测一致则置信度高。
 *
 * 数据源包括：
 * - GFS (美国 NOAA/NCEP)
 * - ECMWF IFS (欧洲中期天气预报中心)
 * - DWD ICON (德国气象局)
 * - JMA (日本气象厅)
 * - MeteoFrance Arpege (法国气象局)
 * - GEM (加拿大环境部)
 */
@Data
@Builder
public class WeatherMultiModelForecast {
    private String city;
    private LocalDate targetDate;
    private List<ModelPoint> modelPoints;
    private Instant fetchedAt;
    private int leadHours;

    @Data
    @Builder
    public static class ModelPoint {
        /** 模型名称，如 "GFS", "ECMWF_IFS", "ICON", "JMA" */
        private String modelName;
        /** 该模型所属机构/国家 */
        private String agency;
        /** 预测最高温 (°F) */
        private BigDecimal forecastMaxF;
        /** 预测最低温 (°F) */
        private BigDecimal forecastMinF;
    }
}
