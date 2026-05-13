package com.metaverse.coin.strategy.job.smartwallet.model;
import com.metaverse.coin.strategy.common.service.smartwallet.model.SmartWalletProfile;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SmartWalletSignal {
    private String signalId;
    private SmartWalletProfile wallet;
    private PredictionMarketOpportunity market;
    private String followOutcome;
    private BigDecimal walletScore;
    private BigDecimal signalScore;
    private BigDecimal recommendedStakeUsdc;
    private boolean executable;
    @Builder.Default
    private List<String> reasons = new ArrayList<>();
}
