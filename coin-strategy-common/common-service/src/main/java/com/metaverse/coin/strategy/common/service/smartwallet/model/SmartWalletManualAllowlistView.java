package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Date;

@Builder
public record SmartWalletManualAllowlistView(
        Long id,
        String walletAddress,
        String source,
        String status,
        String allowedExecutionModes,
        BigDecimal stakeMultiplier,
        Integer maxLiveOrdersPerDay,
        Integer maxLiveOrdersPerRun,
        Date expiresAt,
        String reason,
        String createdBy,
        Boolean active,
        Date createdAt,
        Date updatedAt
) {
}
