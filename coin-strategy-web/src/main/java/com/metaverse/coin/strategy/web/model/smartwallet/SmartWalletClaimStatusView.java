package com.metaverse.coin.strategy.web.model.smartwallet;

import java.math.BigDecimal;
import java.util.Date;

public record SmartWalletClaimStatusView(int claimableCount, BigDecimal totalClaimableProceeds,
    int recentRunCount, int activeRetryCount, Date lastRunAt) {}
