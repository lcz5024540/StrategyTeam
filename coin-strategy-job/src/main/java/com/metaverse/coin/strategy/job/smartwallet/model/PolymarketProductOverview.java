package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class PolymarketProductOverview {
    private String mode;
    private boolean discoveryEnabled;
    private boolean eventSyncEnabled;
    private boolean liveExecutionEnabled;
    private int profiles;
    private int watchlist;
    private long tradeEvents;
    private BigDecimal totalDeployedUsdc;
    private int activePositions;
    private Map<String, Long> taskStatusCounts;
    private ExecutionWalletStatus executionWallet;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
