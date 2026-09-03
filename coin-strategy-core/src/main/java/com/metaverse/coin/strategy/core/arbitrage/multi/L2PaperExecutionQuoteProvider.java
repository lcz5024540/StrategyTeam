package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reads a fresh public order book and produces a deterministic Paper fill quote. */
public final class L2PaperExecutionQuoteProvider implements PaperExecutionQuoteProvider {
    private final Map<Exchange, CrossExchangeMarketDataProvider> providers;
    private final Map<Exchange, ExchangeCost> costs;
    private final Duration maximumBookAge;
    private final int depth;

    public L2PaperExecutionQuoteProvider(List<CrossExchangeMarketDataProvider> providers,
            Map<Exchange, ExchangeCost> costs, Duration maximumBookAge, int depth) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                CrossExchangeMarketDataProvider::exchange, Function.identity()));
        this.costs = Map.copyOf(costs);
        this.maximumBookAge = maximumBookAge;
        this.depth = depth;
    }

    @Override
    public ExecutionQuote quote(Exchange exchange, String symbol, HedgeSide side,
            BigDecimal quantity, Instant now) {
        CrossExchangeMarketDataProvider provider = providers.get(exchange);
        if (provider == null) {
            throw new IllegalStateException("market data provider is unavailable: " + exchange);
        }
        NormalizedOrderBookSnapshot book = provider.orderBook(symbol, depth);
        if (book == null || book.receivedAt().plus(maximumBookAge).isBefore(now)) {
            throw new IllegalStateException("order book is missing or stale: " + exchange + " " + symbol);
        }
        boolean buy = side == HedgeSide.LONG;
        List<NormalizedOrderBookSnapshot.Level> levels = buy ? book.askLevels() : book.bidLevels();
        BigDecimal bestPrice = buy ? book.bestAsk() : book.bestBid();
        OrderBookSweep.Result sweep = OrderBookSweep.forQuantity(levels, quantity, bestPrice);
        ExchangeCost cost = costs.get(exchange);
        BigDecimal feeRate = cost == null ? BigDecimal.ZERO : cost.takerFeeRate();
        String fingerprint = Integer.toHexString((exchange.name() + '|' + symbol + '|'
                + book.receivedAt() + '|' + levels).hashCode());
        return new ExecutionQuote(book.receivedAt(), fingerprint, sweep, feeRate);
    }
}
