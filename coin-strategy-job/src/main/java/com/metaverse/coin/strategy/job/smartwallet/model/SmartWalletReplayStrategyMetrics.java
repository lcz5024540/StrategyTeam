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
    private Integer concentrationBlockedCount;
    private Integer extremeConcentrationBlockedCount;
    private Integer concentrationDownsizedAcceptedCount;
    private BigDecimal winRate;
    private BigDecimal winRateLowerBound;
    private BigDecimal winRateUpperBound;
    private BigDecimal grossProfitUsdc;
    private BigDecimal grossLossUsdc;
    private BigDecimal profitFactor;
    private BigDecimal realizedPnlUsdc;
    private BigDecimal settlementProxyPnlUsdc;
    private BigDecimal maxDrawdownUsdc;
    private BigDecimal concentrationBlockedProxyPnlUsdc;
    private BigDecimal concentrationBlockedProxyMaxDrawdownUsdc;
    private BigDecimal extremeConcentrationBlockedProxyPnlUsdc;
    private BigDecimal extremeConcentrationBlockedProxyMaxDrawdownUsdc;
    private BigDecimal averageHoldMinutes;
    @Builder.Default
    private Map<String, Integer> skipReasonDistribution = new LinkedHashMap<>();
}
