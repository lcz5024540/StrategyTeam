package com.metaverse.coin.strategy.job.strategy.model;

import java.util.List;

/**
 * 策略身份信息，只用于 summary 等页面上下文。
 * 数值指标统一放到 metrics，避免 summary / metrics 双口径。
 */
public record StrategyIdentityView(
        String code,
        String name,
        String category,
        String status,
        String description,
        List<String> supportedModes,
        List<String> capabilities,
        String modeBehavior
) {
}
