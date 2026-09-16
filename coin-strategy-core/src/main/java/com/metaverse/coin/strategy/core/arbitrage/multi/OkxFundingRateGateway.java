package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class OkxFundingRateGateway extends ReadOnlyFundingRateGateway {
    public OkxFundingRateGateway() {
        super(Exchange.OKX,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.OKX));
    }

    @Autowired
    public OkxFundingRateGateway(Environment environment) {
        super(Exchange.OKX, configuredClient(Exchange.OKX,
                PerpetualMarketDataClient.Venue.OKX, environment));
    }
}
