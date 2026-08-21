package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class BinanceFundingRateGateway extends ReadOnlyFundingRateGateway {
    public BinanceFundingRateGateway() {
        super(Exchange.BINANCE,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.BINANCE));
    }

    @Autowired
    public BinanceFundingRateGateway(Environment environment) {
        super(Exchange.BINANCE, configuredClient(Exchange.BINANCE,
                PerpetualMarketDataClient.Venue.BINANCE, environment));
    }
}
