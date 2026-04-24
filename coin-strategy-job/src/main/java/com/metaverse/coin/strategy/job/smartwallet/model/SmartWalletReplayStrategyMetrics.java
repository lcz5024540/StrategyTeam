package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class SmartWalletReplayStrategyMetrics {
    private String strategyKey;
    private String policyTag;
    private String exitMode;
    private Integer scannedEvents;
    private Integer acceptedEvents;
    private Integer acceptedWithLabelEvents;
    private BigDecimal acceptanceRate;
    private BigDecimal labelCoverageRate;
    private Integer overrideCount;
    private Integer recentDegradationBlockCount;
    private BigDecimal winRate;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal settlementProxyPnlUsdc;
    private BigDecimal maxDrawdownUsdc;
    private BigDecimal averageHoldMinutes;
    @Builder.Default
    private Map<String, Integer> skipReasonDistribution = new LinkedHashMap<>();
}
