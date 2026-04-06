package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmartWalletBlockerStatView {
    private String blockerCategory;
    private Integer taskCount;
    private Integer executableCount;
    private Integer blockedCount;
}
