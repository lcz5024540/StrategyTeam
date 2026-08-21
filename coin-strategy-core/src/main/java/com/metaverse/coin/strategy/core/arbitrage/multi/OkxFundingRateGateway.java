package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.stereotype.Component;

@Component
public final class OkxFundingRateGateway extends ReadOnlyFundingRateGateway {
    public OkxFundingRateGateway() {
        super(Exchange.OKX,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.OKX));
    }
}
