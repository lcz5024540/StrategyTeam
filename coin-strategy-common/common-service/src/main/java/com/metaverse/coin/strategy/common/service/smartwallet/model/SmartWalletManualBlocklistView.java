package com.metaverse.coin.strategy.common.service.smartwallet.model;

import lombok.Builder;

import java.util.Date;

@Builder
public record SmartWalletManualBlocklistView(
        Long id,
        String walletAddress,
        String source,
        String status,
        String reason,
        String createdBy,
        Boolean active,
        Date createdAt,
        Date updatedAt
) {
}
