package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class ExecutionWalletStatus {
    private boolean enabled;
    private boolean configured;
    private boolean liveReady;
    private String chain;
    private String address;
    private String funderAddress;
    private BigDecimal minOrderUsdc;
    private BigDecimal maxOrderUsdc;
    private BigDecimal priceSlippageRate;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
