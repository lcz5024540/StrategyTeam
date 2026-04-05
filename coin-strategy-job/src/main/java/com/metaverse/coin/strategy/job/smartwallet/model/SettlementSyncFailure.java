package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SettlementSyncFailure {
    private String marketId;
    private String question;
    private String errorMessage;
}
