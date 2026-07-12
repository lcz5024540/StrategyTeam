package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class StrategyExperimentFoldResult {
    private Integer fold;
    private Date trainStart;
    private Date trainEnd;
    private Date validationStart;
    private Date validationEnd;
    private Date testStart;
    private Date testEnd;
    private SmartWalletReplayWindowResult replay;
}
