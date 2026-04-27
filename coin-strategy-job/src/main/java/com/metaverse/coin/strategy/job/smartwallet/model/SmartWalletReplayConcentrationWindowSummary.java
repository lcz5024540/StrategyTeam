package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class SmartWalletReplayConcentrationWindowSummary {
    private Integer windowDays;
    private Integer scannedEvents;
    private String baselinePolicyTag;
    private String candidatePolicyTag;
    private Integer baselineAcceptedEvents;
    private Integer candidateAcceptedEvents;
    private Integer acceptedDelta;
    private BigDecimal baselineAcceptanceRate;
    private BigDecimal candidateAcceptanceRate;
    private BigDecimal acceptanceRateDelta;
    private Integer concentrationBlockedCount;
    private Integer extremeConcentrationBlockedCount;
    private Integer totalConcentrationBlockedCount;
    private BigDecimal concentrationBlockedRate;
    private Integer concentrationDownsizedAcceptedCount;
    private BigDecimal concentrationDownsizedAcceptedRate;
    private BigDecimal concentrationBlockedProxyPnlUsdc;
    private BigDecimal extremeConcentrationBlockedProxyPnlUsdc;
    private BigDecimal totalConcentrationBlockedProxyPnlUsdc;
    private BigDecimal concentrationBlockedProxyMaxDrawdownUsdc;
    private BigDecimal extremeConcentrationBlockedProxyMaxDrawdownUsdc;
    private BigDecimal worstConcentrationBlockedProxyMaxDrawdownUsdc;
    private BigDecimal baselineRealizedPnlUsdc;
    private BigDecimal candidateRealizedPnlUsdc;
    private BigDecimal realizedPnlDeltaUsdc;
    private BigDecimal baselineMaxDrawdownUsdc;
    private BigDecimal candidateMaxDrawdownUsdc;
    private BigDecimal maxDrawdownDeltaUsdc;
    @Builder.Default
    private Map<String, Integer> candidateConcentrationReasonDistribution = new LinkedHashMap<>();
}
