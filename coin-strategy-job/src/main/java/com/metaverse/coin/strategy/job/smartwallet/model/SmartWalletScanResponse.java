package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SmartWalletScanResponse {
    private boolean autoExecutionEnabled;
    private BigDecimal bankrollUsdc;
    @Builder.Default
    private List<SmartWalletSignal> executableSignals = new ArrayList<>();
    @Builder.Default
    private List<SmartWalletSignal> rejectedSignals = new ArrayList<>();
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
