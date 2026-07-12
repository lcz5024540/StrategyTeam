package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class StrategyPromotionView {
    private String strategyKey;
    private String championModelVersion;
    private String challengerModelVersion;
    private String stage;
    private BigDecimal riskBudgetPct;
    private Date stageStartedAt;
    private Integer settledTradeCount;
    private Boolean advanced;
    private Boolean rolledBack;
    @Builder.Default
    private List<String> gateFailures = new ArrayList<>();
    private String lastRollbackReason;
}
