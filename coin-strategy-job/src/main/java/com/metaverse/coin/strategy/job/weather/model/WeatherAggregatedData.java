package com.metaverse.coin.strategy.job.weather.model;

import lombok.Data;

import java.time.Instant;

/**
 * 聚合所有数据源的统一数据结构。
 * 包含集成预报、多模型确定性预报、实时观测和 Tomorrow.io 数据。
 * FairValueServiceV2 根据数据可用性自动选择最优计算路径。
 */
@Data
public class WeatherAggregatedData {
    /** 集成预报（Open-Meteo Ensemble API，82+ 成员） */
    private WeatherEnsembleForecast ensemble;
    /** 多模型确定性预报（GFS/ECMWF/ICON/JMA/Arpege/GEM 各自的单点预测） */
    private WeatherMultiModelForecast multiModel;
    /** Tomorrow.io 确定性预报 */
    private WeatherForecast deterministic;
    /** 实时观测（leadHours < 12 时拉取） */
    private WeatherRealtimeObservation observation;
    /** 预报到结算的小时数 */
    private int leadHours;
    /** 可用独立数据源数量 */
    private int dataSourceCount;
    /** 聚合时间 */
    private Instant aggregatedAt;

    /**
     * 多源共识度评分 [0, 1]。
     * 各源对 threshold 的超越概率估计越一致，共识度越高。
     * consensus = 1 - (各源概率估计的标准差 / 0.25)
     */
    private java.math.BigDecimal consensusScore;
}
