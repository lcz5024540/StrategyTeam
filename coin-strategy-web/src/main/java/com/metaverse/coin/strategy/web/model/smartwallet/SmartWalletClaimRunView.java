package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.Date;

public record SmartWalletClaimRunView(Long id, String status, Integer scannedPositions,
    Integer claimedPositions, Integer failedPositions, String errorMessage,
    Date startedAt, Date finishedAt, Date createdAt) {}
