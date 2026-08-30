package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Public-only Hyperliquid perpetual market-data gateway. It never signs or submits orders. */
@Component
public final class HyperliquidFundingRateGateway extends ReadOnlyFundingRateGateway {
    public HyperliquidFundingRateGateway() {
        super(Exchange.HYPERLIQUID,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.HYPERLIQUID));
    }

    @Autowired
    public HyperliquidFundingRateGateway(Environment environment) {
        super(Exchange.HYPERLIQUID, configuredClient(Exchange.HYPERLIQUID,
                PerpetualMarketDataClient.Venue.HYPERLIQUID, environment));
    }
}
