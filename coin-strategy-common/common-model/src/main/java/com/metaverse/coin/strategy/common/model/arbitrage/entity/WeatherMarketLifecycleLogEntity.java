package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 天气市场生命周期快照记录。
 *
 * 每次采集会扫描 Polymarket 上所有天气相关市场（含已关闭/已结算），
 * 记录市场当前状态、距结算时间、价格等信息，
 * 用于统计分析市场规律：
 *   - 市场提前多久上线 (firstSeenLeadHours)
 *   - 市场何时停止接单 (acceptingOrders → false 时的 leadHours)
 *   - 典型 lead time 分布
 *   - 各城市/温度类型的市场频率
 */
@Data
@Accessors(chain = true)
@TableName("weather_market_lifecycle_log")
public class WeatherMarketLifecycleLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Polymarket conditionId */
    private String marketId;
    /** 市场问题原文 */
    private String question;
    /** 解析出的城市 */
    private String city;
    /** 温度类型: MAX / MIN */
    private String temperatureType;
    /** 阈值温度 (°F) */
    private BigDecimal thresholdF;
    /** 目标日期 (YYYY-MM-DD) */
    private String targetDate;

    // ── 市场状态 ──
    /** 是否活跃 */
    private Boolean active;
    /** 是否关闭 */
    private Boolean closed;
    /** 是否仍接受订单 */
    private Boolean acceptingOrders;
    /** 结算状态: null / RESOLVED / NEED_MANUAL_CHECK 等 */
    private String resolutionStatus;
    /** 已结算的结果: YES / NO / null */
    private String resolvedOutcome;

    // ── 时间戳 ──
    /** 市场结束时间 (epoch millis) */
    private Long endAtMs;
    /** 扫描时计算的 lead hours (距 endAt 的小时数，负数表示已过期) */
    private Integer leadHours;
    /** 首次观察到的 lead hours (仅首次扫描到时记录) */
    private Integer firstSeenLeadHours;

    // ── 价格 & 流动性 ──
    /** YES 价格 [0,1] */
    private BigDecimal yesPrice;
    /** NO 价格 [0,1] */
    private BigDecimal noPrice;

    // ── 采集元数据 ──
    /** 本次采集的批次 ID (UUID) */
    private String batchId;
    /** 采集时间 */
    private Date scannedAt;
    /** 是否为该市场在本表的首次记录 */
    private Boolean firstSeen;
}
