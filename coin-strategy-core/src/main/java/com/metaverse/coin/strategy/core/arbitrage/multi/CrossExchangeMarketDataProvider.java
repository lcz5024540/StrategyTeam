package com.metaverse.coin.strategy.core.arbitrage.multi;
import java.util.List;
public interface CrossExchangeMarketDataProvider {
 Exchange exchange();
 List<String> supportedUsdtPerpetualSymbols();
 NormalizedFundingRateSnapshot fundingRate(String canonicalSymbol);
 NormalizedOrderBookSnapshot orderBook(String canonicalSymbol, int depth);
}
