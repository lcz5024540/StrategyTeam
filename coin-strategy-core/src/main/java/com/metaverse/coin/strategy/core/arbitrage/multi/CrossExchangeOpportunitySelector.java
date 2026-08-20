package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Selects the best executable pair; rejected pairs retain an explicit reason. */
public final class CrossExchangeOpportunitySelector {
    public record Limits(Duration maxQuoteAge, Duration maxSettlementSkew, BigDecimal maxMarkPriceDeviation,
                         BigDecimal minNotional, BigDecimal minNetYield, BigDecimal basisRiskBuffer,
                         boolean allowMixedSigns) { }

    public List<CrossExchangeOpportunity> evaluate(List<NormalizedFundingRateSnapshot> rates,
            Map<Exchange, NormalizedOrderBookSnapshot> books, Map<Exchange, ExchangeCost> costs,
            BigDecimal targetNotional, Limits limits, Instant now) {
        List<CrossExchangeOpportunity> result = new ArrayList<>();
        for (int i = 0; i < rates.size(); i++) for (int j = i + 1; j < rates.size(); j++) {
            var a = rates.get(i); var b = rates.get(j);
            if (!a.canonicalSymbol().equals(b.canonicalSymbol()) || a.exchange() == b.exchange()) continue;
            var low = a.hourlyFundingRate().compareTo(b.hourlyFundingRate()) <= 0 ? a : b;
            var high = low == a ? b : a;
            String reason = rejection(low, high, books, costs, targetNotional, limits, now);
            BigDecimal capacity = capacity(low, high, books).min(targetNotional);
            BigDecimal spread = high.hourlyFundingRate().subtract(low.hourlyFundingRate());
            BigDecimal cost = costs.containsKey(low.exchange()) && costs.containsKey(high.exchange())
                    ? costs.get(low.exchange()).roundTripTakerCost().add(costs.get(high.exchange()).roundTripTakerCost()).add(limits.basisRiskBuffer())
                    : BigDecimal.ZERO;
            BigDecimal net = spread.subtract(cost);
            if (reason == null && net.compareTo(limits.minNetYield()) < 0) reason = "NET_YIELD_BELOW_THRESHOLD";
            Exchange firstLeg = firstLeg(low, high, books);
            result.add(new CrossExchangeOpportunity(UUID.randomUUID().toString(), low.canonicalSymbol(), low, high,
                    spread, net, capacity, firstLeg, reason == null ? CrossExchangeOpportunity.Status.QUALIFIED : CrossExchangeOpportunity.Status.REJECTED,
                    reason, now));
        }
        result.sort(Comparator.comparing(CrossExchangeOpportunity::estimatedNetYield).reversed());
        return result;
    }

    private String rejection(NormalizedFundingRateSnapshot low, NormalizedFundingRateSnapshot high,
            Map<Exchange, NormalizedOrderBookSnapshot> books, Map<Exchange, ExchangeCost> costs,
            BigDecimal target, Limits limits, Instant now) {
        if (low.isStale(now, limits.maxQuoteAge()) || high.isStale(now, limits.maxQuoteAge())) return "STALE_FUNDING_QUOTE";
        if (!costs.containsKey(low.exchange()) || !costs.containsKey(high.exchange())) return "MISSING_EXCHANGE_COST";
        if (!books.containsKey(low.exchange()) || !books.containsKey(high.exchange())) return "MISSING_ORDER_BOOK";
        if (Duration.between(low.nextFundingTime(), high.nextFundingTime()).abs().compareTo(limits.maxSettlementSkew()) > 0) return "SETTLEMENT_TIME_SKEW";
        if (!limits.allowMixedSigns()
                && low.rawFundingRate().signum() * high.rawFundingRate().signum() < 0) {
            return "MIXED_FUNDING_SIGNS";
        }
        NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
        NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
        if (longBook.receivedAt().plus(limits.maxQuoteAge()).isBefore(now)
                || shortBook.receivedAt().plus(limits.maxQuoteAge()).isBefore(now)) {
            return "STALE_ORDER_BOOK";
        }
        BigDecimal deviation = high.markPrice().subtract(low.markPrice()).abs().divide(low.markPrice(), 12, RoundingMode.HALF_UP);
        if (deviation.compareTo(limits.maxMarkPriceDeviation()) > 0) return "MARK_PRICE_DEVIATION";
        if (capacity(low, high, books).min(target).compareTo(limits.minNotional()) < 0) return "INSUFFICIENT_LIQUIDITY";
        return null;
    }

    private BigDecimal capacity(NormalizedFundingRateSnapshot low, NormalizedFundingRateSnapshot high,
            Map<Exchange, NormalizedOrderBookSnapshot> books) {
        var l = books.get(low.exchange()); var s = books.get(high.exchange());
        if (l == null || s == null) return BigDecimal.ZERO;
        return l.askNotional().min(s.bidNotional());
    }

    private Exchange firstLeg(NormalizedFundingRateSnapshot low,
            NormalizedFundingRateSnapshot high,
            Map<Exchange, NormalizedOrderBookSnapshot> books) {
        NormalizedOrderBookSnapshot longBook = books.get(low.exchange());
        NormalizedOrderBookSnapshot shortBook = books.get(high.exchange());
        if (longBook == null || shortBook == null) {
            return low.exchange();
        }
        return longBook.askNotional().compareTo(shortBook.bidNotional()) <= 0
                ? low.exchange() : high.exchange();
    }
}
