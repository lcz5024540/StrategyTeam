package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SmartWalletMarketIdRepairRequest {
    private List<Long> positionIds = new ArrayList<>();
    private String canonicalMarketId;
}
