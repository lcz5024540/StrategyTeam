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

    /** A price level whose notional is normalized to USDT, including any contract multiplier. */
    record BookLevel(BigDecimal price, BigDecimal notional) {
        public BookLevel {
            if (price == null || notional == null || price.signum() <= 0 || notional.signum() <= 0) {
                throw new IllegalArgumentException("price and notional must be positive");
            }
        }
    }

    record OrderBook(String canonicalSymbol, BigDecimal bestBid, BigDecimal bestAsk,
            BigDecimal bidNotional, BigDecimal askNotional, List<BookLevel> bidLevels,
            List<BookLevel> askLevels, Instant receivedAt) {
        public OrderBook {
            bidLevels = List.copyOf(bidLevels);
            askLevels = List.copyOf(askLevels);
        }

        /** Compatibility constructor for consumers that only provide aggregate depth. */
        public OrderBook(String canonicalSymbol, BigDecimal bestBid, BigDecimal bestAsk,
                BigDecimal bidNotional, BigDecimal askNotional, Instant receivedAt) {
            this(canonicalSymbol, bestBid, bestAsk, bidNotional, askNotional,
                    List.of(), List.of(), receivedAt);
        }
    }

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
