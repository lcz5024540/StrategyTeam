package com.metaverse.coin.strategy.core.arbitrage.multi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FundingPairRiskSizerTest {
    private final Instant now = Instant.parse("2026-08-31T00:00:00Z");

    @Test
    void usesConservativeDistanceUntilNewSymbolHasEnoughHistory() {
        FundingPairRiskSizer sizer = new FundingPairRiskSizer(limits());

        FundingPairRiskSizer.Sizing sizing = sizer.size(low("100"), high("100"), books(), costs(),
                new BigDecimal("0.001"));

        assertEquals(0, new BigDecimal("0.30").compareTo(sizing.liquidationDistance()));
        assertEquals(0, new BigDecimal("6557.377049180327").compareTo(sizing.targetNotional()));
    }

    @Test
    void increasesRequiredDistanceForObservedVolatility() {
        FundingPairRiskSizer sizer = new FundingPairRiskSizer(limits());
        sizer.record(List.of(low("100"), high("100")), now.minus(Duration.ofHours(1)));
        sizer.record(List.of(low("105"), high("105")), now);

        FundingPairRiskSizer.Sizing sizing = sizer.size(low("105"), high("105"), books(), costs(),
                new BigDecimal("0.001"));

        assertEquals(0, new BigDecimal("0.20").compareTo(sizing.liquidationDistance()));
        assertEquals(0, new BigDecimal("9756.097560975609").compareTo(sizing.targetNotional()));
    }

    private FundingPairRiskSizer.Limits limits() {
        return new FundingPairRiskSizer.Limits(new BigDecimal("2000"), new BigDecimal("25"),
                new BigDecimal("0.005"), new BigDecimal("0.15"), new BigDecimal("0.30"),
                new BigDecimal("0.40"), new BigDecimal("4"), new BigDecimal("3"),
                Duration.ofHours(1), Duration.ofHours(24), new BigDecimal("1.5"),
                new BigDecimal("0.0005"));
    }

    private NormalizedFundingRateSnapshot low(String mark) {
        return rate(Exchange.OKX, "-0.001", mark);
    }

    private NormalizedFundingRateSnapshot high(String mark) {
        return rate(Exchange.HTX, "-0.0001", mark);
    }

    private NormalizedFundingRateSnapshot rate(Exchange exchange, String funding, String mark) {
        return new NormalizedFundingRateSnapshot(exchange, "NEWUSDT", new BigDecimal(funding), 8,
                now.plusSeconds(3600), new BigDecimal(mark), new BigDecimal(mark), now);
    }

    private Map<Exchange, NormalizedOrderBookSnapshot> books() {
        Map<Exchange, NormalizedOrderBookSnapshot> books = new EnumMap<>(Exchange.class);
        books.put(Exchange.OKX, book(Exchange.OKX));
        books.put(Exchange.HTX, book(Exchange.HTX));
        return books;
    }

    private NormalizedOrderBookSnapshot book(Exchange exchange) {
        return new NormalizedOrderBookSnapshot(exchange, "NEWUSDT", new BigDecimal("99.9"),
                new BigDecimal("100"), new BigDecimal("20000"), new BigDecimal("20000"),
                List.of(new NormalizedOrderBookSnapshot.Level(new BigDecimal("99.9"), new BigDecimal("20000"))),
                List.of(new NormalizedOrderBookSnapshot.Level(new BigDecimal("100"), new BigDecimal("20000"))), now);
    }

    private Map<Exchange, ExchangeCost> costs() {
        Map<Exchange, ExchangeCost> costs = new EnumMap<>(Exchange.class);
        costs.put(Exchange.OKX, new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        costs.put(Exchange.HTX, new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        return costs;
    }
}
