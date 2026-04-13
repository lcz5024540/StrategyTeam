package com.metaverse.coin.strategy.job.hedge.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** REST API 视图 — 对冲策略整体概览 */
@Data
@Builder
public class HedgeDashboardView {
    private boolean enabled;
    private boolean autoExecutionEnabled;

    private int openPositions;
    private BigDecimal totalOpenExposureUsdc;
    private BigDecimal expectedTotalProfitUsdc;

    private int totalSettled;
    private BigDecimal totalRealizedPnlUsdc;
    private BigDecimal totalClaimedUsdc;

    private BigDecimal globalFollowExposureUsdc;
    private BigDecimal globalHedgeExposureUsdc;
    private BigDecimal globalTotalExposureUsdc;
    private BigDecimal globalMaxExposureUsdc;

    private List<HedgePositionView> openPositionList;
}
