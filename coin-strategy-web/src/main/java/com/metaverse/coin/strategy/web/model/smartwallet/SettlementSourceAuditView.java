package com.metaverse.coin.strategy.web.model.smartwallet;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * W1-T2 结算源审计报表 web 视图.
 * 与 job 端 SettlementSourceAuditService.SettlementSourceAuditReport 字段对齐.
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Data
@Builder
public class SettlementSourceAuditView {

    private Date generatedAt;
    private long resolutionDelayThresholdHours;
    private int totalMarkets;
    private Map<String, Integer> lifecycleCount;
    private int parseFailureTotal;
    private Map<String, Integer> parseFailureByCategory;
    private int resolutionDelayedCount;
    private int positionPendingSettlementOverdue;
    private int positionResolvedUnclaimedOverdue;
    private int claimRetryCount;
    private int claimRetryWithFailures;
    private int claimFailureMax;
    private double claimFailureAvg;
    /** 任意维度异常 → true, 与 job sink 告警门槛一致. */
    private boolean anomalous;
}
