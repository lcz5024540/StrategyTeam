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
    private NormalizedFundingRateSnapshot rate(Exchange e,String r,int h){return new NormalizedFundingRateSnapshot(e,"BTCUSDT",new BigDecimal(r),h,now.plusSeconds(3600),new BigDecimal("100"),new BigDecimal("100"),now);}
    private Map<Exchange,NormalizedOrderBookSnapshot> books(){Map<Exchange,NormalizedOrderBookSnapshot> m=new EnumMap<>(Exchange.class);for(Exchange e:Exchange.values())m.put(e,new NormalizedOrderBookSnapshot(e,"BTCUSDT",new BigDecimal("99"),new BigDecimal("101"),new BigDecimal("1000"),new BigDecimal("1000"),now));return m;}
    private Map<Exchange,NormalizedOrderBookSnapshot> books(Instant receivedAt){Map<Exchange,NormalizedOrderBookSnapshot> m=new EnumMap<>(Exchange.class);for(Exchange e:Exchange.values())m.put(e,new NormalizedOrderBookSnapshot(e,"BTCUSDT",new BigDecimal("99"),new BigDecimal("101"),new BigDecimal("1000"),new BigDecimal("1000"),receivedAt));return m;}
    private Map<Exchange,ExchangeCost> costs(){Map<Exchange,ExchangeCost> m=new EnumMap<>(Exchange.class);for(Exchange e:Exchange.values())m.put(e,new ExchangeCost(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));return m;}
    private CrossExchangeOpportunitySelector.Limits defaultLimits(){return new CrossExchangeOpportunitySelector.Limits(Duration.ofMinutes(1),Duration.ofMinutes(2),new BigDecimal("0.01"),BigDecimal.ONE,new BigDecimal("-1"),BigDecimal.ZERO,false);}
}
