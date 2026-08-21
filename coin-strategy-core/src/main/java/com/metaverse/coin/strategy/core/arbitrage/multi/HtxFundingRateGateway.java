package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.stereotype.Component;

@Component
public final class HtxFundingRateGateway extends ReadOnlyFundingRateGateway {
    public HtxFundingRateGateway() {
        super(Exchange.HTX,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.HTX));
    }
}
