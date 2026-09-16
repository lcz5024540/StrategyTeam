package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.util.List;

/** Exchange adapter boundary; implementations must normalize REST/WebSocket responses. */
public interface ExchangeFundingGateway {
    Exchange exchange();
    List<FundingRateQuote> fundingRates(List<String> symbols);
    void openHedge(FundingRateSpread spread, String symbol, java.math.BigDecimal quantity);
    void closeHedge(FundingRateSpread spread, String symbol, java.math.BigDecimal quantity);

    /** Execute one directional futures leg. Implementations must be idempotent by client order id. */
    void openLeg(HedgeLeg leg, String clientOrderId);

    void closeLeg(HedgeLeg leg, String clientOrderId);
}
