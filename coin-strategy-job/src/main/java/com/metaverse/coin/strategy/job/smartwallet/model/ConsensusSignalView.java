package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class ConsensusSignalView {
    private String marketId;
    private String question;
    private String outcome;
    private String side;
    private int distinctWallets;
    private BigDecimal totalTriggerSizeUsdc;
    private BigDecimal averageTriggerPrice;
    private BigDecimal consensusScore;
    private Date latestOpenedAt;
    @Builder.Default
    private List<String> walletAddresses = new ArrayList<>();
}
