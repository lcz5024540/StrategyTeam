package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class SmartWalletMarketLinkView {
    private String conditionId;
    private String question;
    private String outcome;
    private String marketSlug;
    private String marketUrl;
    private String externalMarketUrl;
    private String internalMarketUrl;
    private String linkStatus;
    private String metadataSource;
    private String category;
    private Long marketEndAt;
    private Boolean active;
    private Boolean closed;
    private Boolean acceptingOrders;
    private String resolutionStatus;
    private String resolvedOutcome;
    private String winningTokenId;
    private List<String> outcomes;
    private Map<String, String> outcomeTokenIds;
}
