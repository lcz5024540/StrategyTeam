package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PrematureSettlementRepairRequest {
    private List<Long> positionIds = new ArrayList<>();
    private List<PrematureSettlementRepairItem> items = new ArrayList<>();
}
