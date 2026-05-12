package com.metaverse.coin.strategy.web.model.smartwallet;

import java.util.Date;

public record SettlementRunView(Long id, String status, Integer scannedPositions,
    Integer resolvedPositions, Integer failedMarkets, String errorMessage,
    Date startedAt, Date finishedAt, Date createdAt) {}
