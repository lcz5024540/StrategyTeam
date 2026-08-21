package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.stereotype.Component;

@Component
public final class BitgetFundingRateGateway extends ReadOnlyFundingRateGateway {
    public BitgetFundingRateGateway() {
        super(Exchange.BITGET,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.BITGET));
    }
}
