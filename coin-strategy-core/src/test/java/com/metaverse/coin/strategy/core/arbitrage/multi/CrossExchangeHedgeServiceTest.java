package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossExchangeHedgeServiceTest {
    @Test void partialOpenIsRolledBack() {
        FakeGateway binance = new FakeGateway(Exchange.BINANCE, false);
        FakeGateway okx = new FakeGateway(Exchange.OKX, true);
        Map<Exchange, ExchangeFundingGateway> map = new EnumMap<>(Exchange.class);
        map.put(Exchange.BINANCE, binance); map.put(Exchange.OKX, okx);
        var service = new CrossExchangeHedgeService(map);
        var a = new FundingRateQuote(Exchange.BINANCE, "BTCUSDT", new BigDecimal("-0.001"), BigDecimal.ONE, Instant.now());
        var b = new FundingRateQuote(Exchange.OKX, "BTCUSDT", new BigDecimal("-0.0001"), BigDecimal.ONE, Instant.now());
        var result = service.open(FundingRateSpreadCalculator.candidate(a, b, BigDecimal.ZERO, BigDecimal.ZERO), BigDecimal.ONE);
        assertEquals(CrossExchangeHedgeExecution.Status.ROLLED_BACK, result.status());
        assertEquals(2, binance.calls);
    }
    private static final class FakeGateway implements ExchangeFundingGateway {
        final Exchange exchange; final boolean fail; int calls;
        FakeGateway(Exchange exchange, boolean fail) { this.exchange = exchange; this.fail = fail; }
        public Exchange exchange() { return exchange; }
        public List<FundingRateQuote> fundingRates(List<String> s) { return List.of(); }
        public void openHedge(FundingRateSpread s, String x, BigDecimal q) { }
        public void closeHedge(FundingRateSpread s, String x, BigDecimal q) { }
        public void openLeg(HedgeLeg l, String id) { calls++; if (fail) throw new IllegalStateException("failed"); }
        public void closeLeg(HedgeLeg l, String id) { calls++; }
    }
}
