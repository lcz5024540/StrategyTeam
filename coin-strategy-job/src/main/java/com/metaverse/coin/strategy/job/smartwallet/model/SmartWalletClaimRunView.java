package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class SmartWalletClaimRunView {
    private Long id;
    private String triggerType;
    private Integer positionLimit;
    private String status;
    private Integer scannedPositions;
    private Integer attemptedClaims;
    private Integer claimedPositions;
    private Integer waitingConfirmationPositions;
    private Integer failedPositions;
    private Integer skippedPositions;
    private List<String> notes;
    private String errorMessage;
    private Date startedAt;
    private Date finishedAt;
}
