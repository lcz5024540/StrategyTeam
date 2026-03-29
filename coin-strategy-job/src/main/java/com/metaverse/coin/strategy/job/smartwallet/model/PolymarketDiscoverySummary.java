package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class PolymarketDiscoverySummary {
    private int candidateWallets;
    private int analyzedWallets;
    private int importedProfiles;
    private int importedTradeEvents;
    private int qualifiedCount;
    private int watchlistCount;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
