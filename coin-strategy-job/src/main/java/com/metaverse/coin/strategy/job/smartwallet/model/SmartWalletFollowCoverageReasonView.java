package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class SmartWalletFollowCoverageReasonView {
    private String reason;
    private long count;
    private BigDecimal ratio;
    private BigDecimal recommendedStakeUsdc;
}
