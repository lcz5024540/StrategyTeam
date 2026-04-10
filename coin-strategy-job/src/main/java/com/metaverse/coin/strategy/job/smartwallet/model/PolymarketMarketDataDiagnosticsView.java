package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class PolymarketMarketDataDiagnosticsView {
    private boolean wsEnabled;
    private boolean wsConnected;
    private int subscribedTokenCount;
    private int cachedTokenCount;
    private long messagesReceived;
    private long bookEventsReceived;
    private long priceEventsReceived;
    private Date lastEventAt;
    private long wsSnapshotHits;
    private long restFallbacks;
}
