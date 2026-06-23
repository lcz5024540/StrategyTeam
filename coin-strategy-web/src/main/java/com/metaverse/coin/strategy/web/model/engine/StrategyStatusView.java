package com.metaverse.coin.strategy.web.model.engine;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单策略状态概览（用于 Dashboard 列表）。
 */
@Data
@Builder
public class StrategyStatusView {

    private String strategyId;
    private String displayName;
    private boolean enabled;
    private String mode;
    private boolean healthy;
    private int openPositions;
    private BigDecimal totalExposureUsdc;
    private BigDecimal todayRealizedPnlUsdc;
    private BigDecimal unrealizedPnlUsdc;
}
