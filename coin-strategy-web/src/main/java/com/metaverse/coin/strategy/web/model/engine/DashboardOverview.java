package com.metaverse.coin.strategy.web.model.engine;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 全局 Dashboard 概览。
 */
@Data
@Builder
public class DashboardOverview {

    /** 已注册策略总数 */
    private int totalStrategies;

    /** 已启用策略数 */
    private int enabledStrategies;

    /** 健康策略数 */
    private int healthyStrategies;

    /** 总持仓数 */
    private int totalOpenPositions;

    /** 全局总曝险(USDC) */
    private BigDecimal totalExposureUsdc;

    /** 今日全局已实现PnL */
    private BigDecimal todayRealizedPnlUsdc;

    /** 全局未实现PnL */
    private BigDecimal unrealizedPnlUsdc;

    /** 活跃告警数 */
    private int activeAlerts;

    /** 今日运行总次数 */
    private int todayRunCount;

    /** 各策略状态列表 */
    private List<StrategyStatusView> strategies;
}
