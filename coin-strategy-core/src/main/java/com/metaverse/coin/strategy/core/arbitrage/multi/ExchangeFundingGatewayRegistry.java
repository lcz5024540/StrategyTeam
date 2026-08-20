package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.util.EnumMap;
import java.util.Map;

/** Explicit registry used by the comparison stage; missing adapters are never silently treated as zero rates. */
public final class ExchangeFundingGatewayRegistry {
    private final Map<Exchange, ExchangeFundingGateway> gateways = new EnumMap<>(Exchange.class);

    public ExchangeFundingGatewayRegistry register(ExchangeFundingGateway gateway) {
        if (gateway == null || gateway.exchange() == null) throw new IllegalArgumentException("gateway is required");
        gateways.put(gateway.exchange(), gateway);
        return this;
    }

    public ExchangeFundingGateway require(Exchange exchange) {
        ExchangeFundingGateway gateway = gateways.get(exchange);
        if (gateway == null) throw new IllegalStateException("No funding gateway configured for " + exchange);
        return gateway;
    }

    public Map<Exchange, ExchangeFundingGateway> snapshot() {
        return Map.copyOf(gateways);
    }
}
