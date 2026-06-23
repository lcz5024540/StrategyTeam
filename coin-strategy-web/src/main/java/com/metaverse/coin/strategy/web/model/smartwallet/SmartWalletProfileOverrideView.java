package com.metaverse.coin.strategy.web.model.smartwallet;
import java.util.Date;
public record SmartWalletProfileOverrideView(String walletAddress, String alias, Boolean qualified, Boolean watchEnabled,
    Boolean manualWatchEnabled, String manualRecommendedExitLane, String manualOverrideNote, Date manualOverrideUpdatedAt) {}
