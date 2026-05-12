package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.Date;

public record SmartWalletClaimRetryView(Long id, Long positionId, String marketId, String question,
    String status, Integer failureCount, String lastErrorMessage,
    Date nextRetryAt, Date createdAt, Date updatedAt) {}
