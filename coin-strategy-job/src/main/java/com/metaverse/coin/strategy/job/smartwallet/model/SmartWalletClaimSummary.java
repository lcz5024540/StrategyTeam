package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SmartWalletClaimSummary {
    private Integer scannedPositions;
    private Integer attemptedClaims;
    private Integer claimedPositions;
    private Integer waitingConfirmationPositions;
    private Integer failedPositions;
    private Integer skippedPositions;
    @Builder.Default
    private List<String> notes = new ArrayList<>();
    @Builder.Default
    private List<SmartWalletClaimFailure> failures = new ArrayList<>();
    @Builder.Default
    private List<Long> processedPositionIds = new ArrayList<>();
    @Builder.Default
    private List<Long> claimedPositionIds = new ArrayList<>();
}
