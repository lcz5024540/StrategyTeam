package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class LiveValidationStatusView {
    private boolean enabled;
    private String preset;
    private Integer candidateScanLimit;
    private Integer maxExecutionsPerRun;
    private boolean skipIfPaperPendingExists;
    private boolean suspended;
    private Date suspendedUntil;
    private String suspensionReason;
    private boolean running;
    private Date lastRunAt;
    private LiveValidationRunSummary lastRunSummary;
}
