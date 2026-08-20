package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FundingRateSpreadCalculatorTest {
    @Test void choosesLowFundingLongAndHighFundingShort() {
        var a = new FundingRateQuote(Exchange.BINANCE, "BTCUSDT", new BigDecimal("-0.0001"), new BigDecimal("100"), Instant.now());
        var b = new FundingRateQuote(Exchange.OKX, "BTCUSDT", new BigDecimal("0.0010"), new BigDecimal("101"), Instant.now());
        var result = FundingRateSpreadCalculator.best(List.of(a, b), new BigDecimal("0.0001"), BigDecimal.ZERO, new BigDecimal("0.0005")).orElseThrow();
        assertEquals(Exchange.BINANCE, result.longLeg().exchange());
        assertEquals(Exchange.OKX, result.shortLeg().exchange());
        assertEquals(new BigDecimal("0.0011"), result.fundingSpread());
    }
    @Test void rejectsInsufficientQuotes() {
        assertTrue(FundingRateSpreadCalculator.best(List.of(new FundingRateQuote(Exchange.HTX, "BTCUSDT", BigDecimal.ZERO, BigDecimal.ONE, Instant.now())), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).isEmpty());
    }
}
