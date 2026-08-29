package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class CrossExchangeOpportunityScanService {
    private final List<CrossExchangeMarketDataProvider> providers;
    private final CrossExchangeOpportunitySelector selector;
    private final CrossExchangeOpportunityStabilityGate gate;
    private final CrossExchangeOpportunitySelector.Limits limits;
    private final Map<Exchange, ExchangeCost> costs;
    private final BigDecimal targetNotional;
    private final ExchangeHealthRegistry health;
    private final PerpetualContractCatalog catalog;
    private final Map<String, CrossExchangeOpportunity> approved = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<CrossExchangeOpportunity> lastEvaluated = List.of();

    public CrossExchangeOpportunityScanService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeOpportunitySelector selector, CrossExchangeOpportunityStabilityGate gate,
            CrossExchangeOpportunitySelector.Limits limits, Map<Exchange, ExchangeCost> costs,
            BigDecimal targetNotional, ExchangeHealthRegistry health) {
        this(providers, selector, gate, limits, costs, targetNotional, health, null);
    }

    public CrossExchangeOpportunityScanService(List<CrossExchangeMarketDataProvider> providers,
            CrossExchangeOpportunitySelector selector, CrossExchangeOpportunityStabilityGate gate,
            CrossExchangeOpportunitySelector.Limits limits, Map<Exchange, ExchangeCost> costs,
            BigDecimal targetNotional, ExchangeHealthRegistry health, PerpetualContractCatalog catalog) {
        this.providers = List.copyOf(providers);
        this.selector = selector;
        this.gate = gate;
        this.limits = limits;
        this.costs = costs;
        this.targetNotional = targetNotional;
        this.health = health;
        this.catalog = catalog;
    }

    public List<CrossExchangeOpportunity> scan(String symbol, Instant now) {
        List<CrossExchangeOpportunity> evaluated = evaluate(symbol, now);
        List<CrossExchangeOpportunity> stable = new ArrayList<>();
        for (int index = 0; index < evaluated.size(); index++) {
            CrossExchangeOpportunity item = withStableId(evaluated.get(index), now);
            if (gate.stable(item, now)) {
                item = approved(item);
                approved.put(item.opportunityId(), item);
                stable.add(item);
            }
            evaluated.set(index, item);
        }
        gate.completeScan(symbol, evaluated);
        lastEvaluated = List.copyOf(evaluated);
        removeStaleApproved(now);
        return stable;
    }

    /** Requotes an approved opportunity immediately before opening it. */
    public Optional<CrossExchangeOpportunity> revalidateApprovedOpportunity(String opportunityId,
            Instant now) {
        CrossExchangeOpportunity existing = approvedOpportunity(opportunityId, now).orElse(null);
        if (existing == null) {
            return Optional.empty();
        }
        return evaluate(existing.symbol(), now).stream()
                .filter(CrossExchangeOpportunity::executable)
                .filter(candidate -> candidate.longQuote().exchange() == existing.longQuote().exchange()
                        && candidate.shortQuote().exchange() == existing.shortQuote().exchange())
                .findFirst()
                .map(candidate -> {
                    CrossExchangeOpportunity refreshed = new CrossExchangeOpportunity(opportunityId,
                            candidate.symbol(), candidate.longQuote(), candidate.shortQuote(),
                            candidate.hourlyFundingSpread(), candidate.estimatedNetYield(),
                            candidate.longEntryPrice(), candidate.shortEntryPrice(),
                            candidate.executableNotional(), candidate.firstLegExchange(),
                            CrossExchangeOpportunity.Status.APPROVED, null, now);
                    approved.put(opportunityId, refreshed);
                    return refreshed;
                });
    }

    public List<CrossExchangeOpportunity> lastEvaluated() {
        return lastEvaluated;
    }

    public Optional<CrossExchangeOpportunity> approvedOpportunity(String opportunityId, Instant now) {
        CrossExchangeOpportunity opportunity = approved.get(opportunityId);
        if (opportunity == null) {
            return Optional.empty();
        }
        if (opportunity.longQuote().isStale(now, limits.maxQuoteAge())
                || opportunity.shortQuote().isStale(now, limits.maxQuoteAge())) {
            approved.remove(opportunityId);
            return Optional.empty();
        }
        return Optional.of(opportunity);
    }

    public Set<String> commonSymbols() {
        Instant now = Instant.now();
        if (catalog != null) {
            catalog.ensureInitialized(now);
            Map<String, Integer> counts = new HashMap<>();
            catalog.snapshot().values().forEach(exchangeContracts -> exchangeContracts.keySet()
                    .forEach(symbol -> counts.merge(symbol, 1, Integer::sum)));
            Set<String> result = new HashSet<>();
            counts.forEach((symbol, count) -> {
                if (count >= 2) {
                    result.add(symbol);
                }
            });
            return Set.copyOf(result);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (CrossExchangeMarketDataProvider provider : providers) {
            try {
                for (String symbol : new HashSet<>(provider.supportedUsdtPerpetualSymbols())) {
                    counts.merge(symbol, 1, Integer::sum);
                }
                health.success(provider.exchange(), now);
            } catch (RuntimeException exception) {
                health.failure(provider.exchange(), now, exception);
            }
        }
        Set<String> result = new HashSet<>();
        counts.forEach((symbol, count) -> {
            if (count >= 2) {
                result.add(symbol);
            }
        });
        return Set.copyOf(result);
    }

    private List<CrossExchangeOpportunity> evaluate(String symbol, Instant now) {
        if (catalog != null) {
            catalog.ensureInitialized(now);
        }
        List<NormalizedFundingRateSnapshot> rates = new ArrayList<>();
        Map<Exchange, NormalizedOrderBookSnapshot> books = new EnumMap<>(Exchange.class);
        for (CrossExchangeMarketDataProvider provider : providers) {
            if (catalog != null && catalog.find(provider.exchange(), symbol).isEmpty()) {
                continue;
            }
            Instant requestStarted = Instant.now();
            try {
                NormalizedFundingRateSnapshot rate = provider.fundingRate(symbol);
                NormalizedOrderBookSnapshot book = provider.orderBook(symbol, 20);
                Instant completedAt = Instant.now();
                long latencyMs = Duration.between(requestStarted, completedAt).toMillis();
                if (Duration.ofMillis(latencyMs).compareTo(limits.maxQuoteAge()) > 0) {
                    throw new IllegalStateException("market data request exceeded latency limit");
                }
                rates.add(rate);
                books.put(provider.exchange(), book);
                health.success(provider.exchange(), now, latencyMs);
            } catch (RuntimeException exception) {
                health.failure(provider.exchange(), now, exception,
                        Duration.between(requestStarted, Instant.now()).toMillis());
            }
        }
        return new ArrayList<>(selector.evaluate(rates, books, costs, targetNotional, limits, now));
    }

    private CrossExchangeOpportunity withStableId(CrossExchangeOpportunity opportunity, Instant now) {
        long windowMillis = Math.max(1L, gate.duration().toMillis());
        String raw = opportunity.symbol() + ":" + opportunity.longQuote().exchange() + ":"
                + opportunity.shortQuote().exchange() + ":" + (now.toEpochMilli() / windowMillis);
        return new CrossExchangeOpportunity(UUID.nameUUIDFromBytes(
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), opportunity.symbol(),
                opportunity.longQuote(), opportunity.shortQuote(), opportunity.hourlyFundingSpread(),
                opportunity.estimatedNetYield(), opportunity.longEntryPrice(), opportunity.shortEntryPrice(),
                opportunity.executableNotional(), opportunity.firstLegExchange(), opportunity.status(),
                opportunity.rejectionReason(), opportunity.discoveredAt());
    }

    private CrossExchangeOpportunity approved(CrossExchangeOpportunity opportunity) {
        return new CrossExchangeOpportunity(opportunity.opportunityId(), opportunity.symbol(),
                opportunity.longQuote(), opportunity.shortQuote(), opportunity.hourlyFundingSpread(),
                opportunity.estimatedNetYield(), opportunity.longEntryPrice(), opportunity.shortEntryPrice(),
                opportunity.executableNotional(), opportunity.firstLegExchange(),
                CrossExchangeOpportunity.Status.APPROVED, null, opportunity.discoveredAt());
    }

    private void removeStaleApproved(Instant now) {
        approved.entrySet().removeIf(entry -> entry.getValue().longQuote().isStale(now, limits.maxQuoteAge())
                || entry.getValue().shortQuote().isStale(now, limits.maxQuoteAge()));
    }
}
