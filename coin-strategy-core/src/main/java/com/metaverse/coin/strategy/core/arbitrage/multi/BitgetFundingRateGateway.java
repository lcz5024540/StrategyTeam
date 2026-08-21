package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class BitgetFundingRateGateway extends ReadOnlyFundingRateGateway {
    public BitgetFundingRateGateway() {
        super(Exchange.BITGET,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.BITGET));
    }

    @Autowired
    public BitgetFundingRateGateway(Environment environment) {
        super(Exchange.BITGET, configuredClient(Exchange.BITGET,
                PerpetualMarketDataClient.Venue.BITGET, environment));
    }
}
