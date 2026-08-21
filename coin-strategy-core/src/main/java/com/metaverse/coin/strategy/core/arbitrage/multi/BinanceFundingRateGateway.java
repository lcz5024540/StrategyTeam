package com.metaverse.coin.strategy.core.arbitrage.multi;

import com.metaverse.coin.strategy.common.client.perpetual.PerpetualMarketDataClient;
import org.springframework.stereotype.Component;

@Component
public final class BinanceFundingRateGateway extends ReadOnlyFundingRateGateway {
    public BinanceFundingRateGateway() {
        super(Exchange.BINANCE,
                PerpetualMarketDataClient.forVenue(PerpetualMarketDataClient.Venue.BINANCE));
    }
}
