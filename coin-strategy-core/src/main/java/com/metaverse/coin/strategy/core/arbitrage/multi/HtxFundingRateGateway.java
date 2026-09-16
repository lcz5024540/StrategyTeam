package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class HtxFundingRateGateway extends ReadOnlyFundingRateGateway {
    public HtxFundingRateGateway() {
        super(Exchange.HTX,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.HTX));
    }

    @Autowired
    public HtxFundingRateGateway(Environment environment) {
        super(Exchange.HTX, configuredClient(Exchange.HTX,
                PerpetualMarketDataClient.Venue.HTX, environment));
    }
}
