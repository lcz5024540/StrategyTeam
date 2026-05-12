package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletManualAllowlistView(Long id, String walletAddress, String source, String status,
    String allowedExecutionModes, BigDecimal stakeMultiplier, Integer maxLiveOrdersPerDay,
    Integer maxLiveOrdersPerRun, Date expiresAt, String reason, String createdBy,
    boolean active, Date createdAt, Date updatedAt) {}
