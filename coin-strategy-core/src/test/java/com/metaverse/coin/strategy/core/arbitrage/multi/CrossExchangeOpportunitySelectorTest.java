package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossExchangeOpportunitySelectorTest {
    private final Instant now = Instant.parse("2026-08-20T00:00:00Z");
    @Test void normalizesIntervalsAndSelectsCorrectDirection() {
        var binance = rate(Exchange.BINANCE, "-0.0008", 8);
        var okx = rate(Exchange.OKX, "-0.0002", 4);
        var limits = new CrossExchangeOpportunitySelector.Limits(Duration.ofMinutes(1), Duration.ofMinutes(2),
                new BigDecimal("0.01"), new BigDecimal("10"), new BigDecimal("-1"), BigDecimal.ZERO, false);
        var result = new CrossExchangeOpportunitySelector().evaluate(List.of(binance, okx), books(), costs(), new BigDecimal("100"), limits, now);
        assertEquals(Exchange.BINANCE, result.get(0).longQuote().exchange());
        assertEquals(Exchange.OKX, result.get(0).shortQuote().exchange());
        assertEquals(CrossExchangeOpportunity.Status.QUALIFIED, result.get(0).status());
    }

    @Test
    void zeroAndPositiveRatesAreNotMixedSigns() {
        List<CrossExchangeOpportunity> opportunities = new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.BINANCE, "0", 8), rate(Exchange.OKX, "0.001", 8)),
                books(), costs(), new BigDecimal("100"), defaultLimits(), now);

        assertNotEquals("MIXED_FUNDING_SIGNS", opportunities.get(0).rejectionReason());
    }

    @Test
    void staleOrderBookIsRejected() {
        Map<Exchange, NormalizedOrderBookSnapshot> staleBooks = books(now.minusSeconds(61));
        List<CrossExchangeOpportunity> opportunities = new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.BINANCE, "0.0001", 8), rate(Exchange.OKX, "0.001", 8)),
                staleBooks, costs(), new BigDecimal("100"), defaultLimits(), now);

        assertEquals("STALE_ORDER_BOOK", opportunities.get(0).rejectionReason());
    }
    @Test void projectsHourlyFundingSpreadOverTheLongestSettlementInterval() {
        var result = new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.BINANCE, "-0.0008", 8), rate(Exchange.OKX, "-0.0002", 4)),
                books(), costs(), new BigDecimal("100"), defaultLimits(), now);

        assertEquals(0, new BigDecimal("0.0004").compareTo(result.get(0).estimatedNetYield()));
        assertEquals(0, new BigDecimal("0.00005").compareTo(result.get(0).hourlyFundingSpread()));
    }
    @Test void projectsHyperliquidHourlyRateAcrossCexSettlementInterval() {
        CrossExchangeOpportunity opportunity = new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.OKX, "0.0008", 8),
                        rate(Exchange.HYPERLIQUID, "0.00015", 1)),
                books(), costs(), new BigDecimal("100"), defaultLimits(), now).get(0);

        assertEquals(Exchange.OKX, opportunity.longQuote().exchange());
        assertEquals(Exchange.HYPERLIQUID, opportunity.shortQuote().exchange());
        assertEquals(0, new BigDecimal("0.00005").compareTo(opportunity.hourlyFundingSpread()));
        assertEquals(0, new BigDecimal("0.0004").compareTo(opportunity.estimatedNetYield()));
    }
    @Test void rejectsWhenAmortizedCapitalTransferCostEliminatesNetYield() {
        Map<Exchange, ExchangeCost> costs = costs();
        costs.put(Exchange.BINANCE, new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.00025")));
        costs.put(Exchange.HYPERLIQUID, new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.00025")));
        CrossExchangeOpportunitySelector.Limits limits = new CrossExchangeOpportunitySelector.Limits(
                Duration.ofMinutes(1), Duration.ofMinutes(2), new BigDecimal("0.01"),
                BigDecimal.ONE, new BigDecimal("0.0003"), BigDecimal.ZERO, false);

        CrossExchangeOpportunity opportunity = new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.BINANCE, "0.0001", 8),
                        rate(Exchange.HYPERLIQUID, "0.0006", 8)),
                books(), costs, new BigDecimal("100"), limits, now).get(0);

        assertEquals(0, BigDecimal.ZERO.compareTo(opportunity.estimatedNetYield()));
        assertEquals("COST_COVERAGE_BELOW_THRESHOLD", opportunity.rejectionReason());
    }
    @Test void rejectsWhenExecutableBidAskBasisExceedsLimit() {
        Map<Exchange, NormalizedOrderBookSnapshot> orderBooks = books();
        orderBooks.put(Exchange.OKX, new NormalizedOrderBookSnapshot(Exchange.OKX, "BTCUSDT",
                new BigDecimal("110"), new BigDecimal("111"), new BigDecimal("1000"),
                new BigDecimal("1000"), now));
        var limits = new CrossExchangeOpportunitySelector.Limits(Duration.ofMinutes(1), Duration.ofMinutes(2),
                new BigDecimal("0.20"), BigDecimal.ONE, new BigDecimal("-1"), BigDecimal.ZERO,
                false, BigDecimal.ONE, new BigDecimal("0.003"));
        var result = new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.BINANCE, "-0.001", 8), rate(Exchange.OKX, "-0.0001", 8)),
                orderBooks, costs(), BigDecimal.TEN, limits, now);

        assertEquals("EXECUTABLE_BASIS_DEVIATION", result.get(0).rejectionReason());
    }
    @Test void rejectsMixedSignsByDefault() {
        var limits = new CrossExchangeOpportunitySelector.Limits(Duration.ofMinutes(1), Duration.ofMinutes(2),
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("-1"), BigDecimal.ZERO, false);
        var result = new CrossExchangeOpportunitySelector().evaluate(List.of(rate(Exchange.BINANCE,"-0.001",8), rate(Exchange.OKX,"0.001",8)),
                books(), costs(), BigDecimal.TEN, limits, now);
        assertEquals("MIXED_FUNDING_SIGNS", result.get(0).rejectionReason());
    }
    @Test void lessLiquidSideIsSelectedAsFirstLeg() {
        Map<Exchange,NormalizedOrderBookSnapshot> orderBooks=books();
        orderBooks.put(Exchange.OKX,new NormalizedOrderBookSnapshot(Exchange.OKX,"BTCUSDT",
                new BigDecimal("99"),new BigDecimal("101"),new BigDecimal("20"),
                new BigDecimal("1000"),now));
        var result=new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.BINANCE,"-0.001",8),rate(Exchange.OKX,"-0.0001",8)),
                orderBooks,costs(),new BigDecimal("100"),defaultLimits(),now);
        assertEquals(Exchange.OKX,result.get(0).firstLegExchange());
    }
    @Test void usesL2VwapAndCommonBaseQuantityWhenBothBooksProvideDepth() {
        Map<Exchange, NormalizedOrderBookSnapshot> orderBooks = new EnumMap<>(Exchange.class);
        orderBooks.put(Exchange.BINANCE, book(Exchange.BINANCE, "100", "101",
                List.of(level("100", "100")), List.of(level("101", "101"), level("103", "206"))));
        orderBooks.put(Exchange.OKX, book(Exchange.OKX, "102", "103",
                List.of(level("102", "102"), level("100", "100")), List.of(level("103", "103"))));

        CrossExchangeOpportunity opportunity = new CrossExchangeOpportunitySelector().evaluate(
                List.of(rate(Exchange.BINANCE, "-0.001", 8), rate(Exchange.OKX, "-0.0001", 8)),
                orderBooks, costs(), new BigDecimal("250"), defaultLimits(), now).get(0);

        assertEquals(0, new BigDecimal("102").compareTo(opportunity.longEntryPrice()));
        assertEquals(0, new BigDecimal("101").compareTo(opportunity.shortEntryPrice()));
        assertEquals(0, new BigDecimal("204").compareTo(opportunity.executableNotional()));
    }
    private NormalizedFundingRateSnapshot rate(Exchange e,String r,int h){return new NormalizedFundingRateSnapshot(e,"BTCUSDT",new BigDecimal(r),h,now.plusSeconds(3600),new BigDecimal("100"),new BigDecimal("100"),now);}
    private Map<Exchange,NormalizedOrderBookSnapshot> books(){Map<Exchange,NormalizedOrderBookSnapshot> m=new EnumMap<>(Exchange.class);for(Exchange e:Exchange.values())m.put(e,new NormalizedOrderBookSnapshot(e,"BTCUSDT",new BigDecimal("99"),new BigDecimal("101"),new BigDecimal("1000"),new BigDecimal("1000"),now));return m;}
    private Map<Exchange,NormalizedOrderBookSnapshot> books(Instant receivedAt){Map<Exchange,NormalizedOrderBookSnapshot> m=new EnumMap<>(Exchange.class);for(Exchange e:Exchange.values())m.put(e,new NormalizedOrderBookSnapshot(e,"BTCUSDT",new BigDecimal("99"),new BigDecimal("101"),new BigDecimal("1000"),new BigDecimal("1000"),receivedAt));return m;}
    private Map<Exchange,ExchangeCost> costs(){Map<Exchange,ExchangeCost> m=new EnumMap<>(Exchange.class);for(Exchange e:Exchange.values())m.put(e,new ExchangeCost(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));return m;}
    private CrossExchangeOpportunitySelector.Limits defaultLimits(){return new CrossExchangeOpportunitySelector.Limits(Duration.ofMinutes(1),Duration.ofMinutes(2),new BigDecimal("0.01"),BigDecimal.ONE,new BigDecimal("-1"),BigDecimal.ZERO,false);}
    private NormalizedOrderBookSnapshot book(Exchange exchange, String bid, String ask,
            List<NormalizedOrderBookSnapshot.Level> bids, List<NormalizedOrderBookSnapshot.Level> asks) {
        return new NormalizedOrderBookSnapshot(exchange, "BTCUSDT", new BigDecimal(bid),
                new BigDecimal(ask), new BigDecimal("1000"), new BigDecimal("1000"), bids, asks, now);
    }
    private NormalizedOrderBookSnapshot.Level level(String price, String notional) {
        return new NormalizedOrderBookSnapshot.Level(new BigDecimal(price), new BigDecimal(notional));
    }
}
