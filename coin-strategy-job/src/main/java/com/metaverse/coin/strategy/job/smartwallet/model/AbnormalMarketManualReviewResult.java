package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AbnormalMarketManualReviewResult {
    private String action;
    private Integer scannedOrders;
    private Integer updatedOrders;
    private Integer updatedPositions;
    private String canonicalMarketId;
    private String eventSlug;
    private String tokenId;
    private List<Long> orderIds;
    private List<Long> positionIds;
    private List<String> notes;
}
