package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.List;

@Data
public class AbnormalMarketManualReviewRequest {
    private String action;
    private List<Long> orderIds;
    private List<Long> positionIds;
    private String marketId;
    private String canonicalMarketId;
    private String tokenId;
    private String eventUrl;
    private String operator;
    private String externalTradeId;
    private String evidenceUrl;
    private String note;
}
