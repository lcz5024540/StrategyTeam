package com.metaverse.coin.strategy.job.engine.monitor.model;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyAlertEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyRunLogEntity;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单策略详细信息视图。
 */
@Data
@Builder
public class StrategyDetailView {

    // ── 基本信息 ──
    private String strategyId;
    private String displayName;
    private boolean enabled;
    private String mode;
    private String walletId;

    // ── 运行状态 ──
    private boolean healthy;
    private int openPositions;
    private int pendingOrders;
    private BigDecimal walletBalanceUsdc;
    private BigDecimal totalExposureUsdc;
    private BigDecimal todayRealizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
    private String notes;

    // ── 今日统计 ──
    private int todayRunCount;
    private int todaySignalsFound;
    private int todayOrdersPlaced;
    private int todayErrors;

    // ── 告警 ──
    private List<StrategyAlertEntity> activeAlerts;

    // ── 最近运行日志 ──
    private List<StrategyRunLogEntity> recentLogs;
}
