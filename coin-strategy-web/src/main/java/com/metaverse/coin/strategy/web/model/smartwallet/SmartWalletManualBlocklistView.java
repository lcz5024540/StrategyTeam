package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.Date;

public record SmartWalletManualBlocklistView(Long id, String walletAddress, String source, String status,
    String reason, String createdBy, boolean active, Date createdAt, Date updatedAt) {}
