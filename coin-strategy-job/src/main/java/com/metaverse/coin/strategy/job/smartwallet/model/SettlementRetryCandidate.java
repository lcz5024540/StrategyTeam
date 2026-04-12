package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SettlementRetryCandidate {
    private String marketId;
    private String question;
    private String retryMessage;
    private String retryType;
}
