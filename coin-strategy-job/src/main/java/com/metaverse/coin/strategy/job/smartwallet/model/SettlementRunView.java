package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class SettlementRunView {
    private Long id;
    private String triggerType;
    private Integer positionLimit;
    private Integer marketLimit;
    private Boolean refreshCache;
    private String status;
    private Integer scannedPositions;
    private Integer distinctMarkets;
    private Integer fetchedMarkets;
    private Integer resolvedPositions;
    private Integer unresolvedMarkets;
    private Integer skippedFutureMarkets;
    private Integer failedMarkets;
    private List<String> notes;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
}
