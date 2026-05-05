package com.metaverse.coin.strategy.job.strategy.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 策略注册与运行摘要，只用于 overview / Topbar / Monitor。
 * 不承载策略详情、风险拆解、任务信号或策略专属字段。
 */
public record StrategyRegistryView(
        String code,
        String name,
        String category,
        String status,
        String description,
        List<String> supportedModes,
        List<String> capabilities,
        String modeBehavior,
        Integer openPositions,
        BigDecimal netPnlUsdc,
        BigDecimal winRate,
        Integer totalOrders
) {
}
