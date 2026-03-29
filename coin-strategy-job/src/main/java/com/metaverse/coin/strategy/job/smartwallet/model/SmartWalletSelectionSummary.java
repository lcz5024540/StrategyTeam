package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SmartWalletSelectionSummary {
    private int qualifiedCount;
    private int rejectedCount;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
