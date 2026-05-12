package com.metaverse.coin.strategy.web.model.smartwallet;
import java.util.Date;
public record SmartWalletProfileStaleDiagnosticView(String walletAddress, String alias, String monitorTier,
    Boolean qualified, Boolean watchEnabled, Date lastTradeEventAt, Date lastSyncedAt,
    Long staleDaysNoTrade, Long staleDaysNoSync) {}
