package com.metaverse.coin.strategy.common.client.perpetual;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Exchange-neutral boundary for public USDT perpetual market data. */
public interface PerpetualMarketDataClient {
    enum Venue { BINANCE, OKX, HTX, BITGET }

    record Contract(String canonicalSymbol, String exchangeSymbol, int quantityScale,
            int priceScale, BigDecimal minimumNotional) { }

    record Funding(String canonicalSymbol, BigDecimal rawRate, int intervalHours,
            Instant nextFundingTime, BigDecimal markPrice, BigDecimal indexPrice,
            Instant receivedAt) { }

    record OrderBook(String canonicalSymbol, BigDecimal bestBid, BigDecimal bestAsk,
            BigDecimal bidNotional, BigDecimal askNotional, Instant receivedAt) { }

    List<Contract> contracts();

    Funding funding(String symbol);

    OrderBook orderBook(String symbol, int depth);

    final class RateLimitException extends RuntimeException {
        public RateLimitException(Throwable cause) {
            super("public perpetual API rate limit exceeded", cause);
        }
    }

    static PerpetualMarketDataClient forVenue(Venue venue) {
        return new PerpetualPublicClient(venue);
    }

    static PerpetualMarketDataClient forVenue(Venue venue, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("perpetual public base URL is required");
        }
        return new PerpetualPublicClient(venue, java.net.URI.create(baseUrl));
    }
}
