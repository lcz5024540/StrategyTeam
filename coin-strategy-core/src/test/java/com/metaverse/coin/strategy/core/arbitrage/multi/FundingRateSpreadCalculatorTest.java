package com.metaverse.coin.strategy.core.arbitrage.multi;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
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
        assertEquals(0, new BigDecimal("0.0011").compareTo(result.fundingSpread()));
    }
    @Test void rejectsInsufficientQuotes() {
        assertTrue(FundingRateSpreadCalculator.best(List.of(new FundingRateQuote(Exchange.HTX, "BTCUSDT", BigDecimal.ZERO, BigDecimal.ONE, Instant.now())), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).isEmpty());
    }

    @Test
    void deductsAmortizedCapitalTransferCostForEachLeg() {
        FundingRateQuote low = new FundingRateQuote(Exchange.OKX, "BTCUSDT",
                new BigDecimal("0.0002"), new BigDecimal("100"), Instant.now());
        FundingRateQuote high = new FundingRateQuote(Exchange.HYPERLIQUID, "BTCUSDT",
                new BigDecimal("0.0010"), new BigDecimal("100"), Instant.now());
        ExchangeCost longCost = new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.0001"));
        ExchangeCost shortCost = new ExchangeCost(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("0.0002"));

        FundingRateSpread result = FundingRateSpreadCalculator.candidate(low, high, longCost, shortCost);

        assertEquals(0, new BigDecimal("0.0005").compareTo(result.estimatedNetYield()));
    }

    @Test
    void projectsHyperliquidHourlyFundingAcrossAnEightHourCexPeriod() {
        FundingRateQuote cex = new FundingRateQuote(Exchange.OKX, "BTCUSDT",
                new BigDecimal("0.0008"), 8, new BigDecimal("100"), Instant.now());
        FundingRateQuote hyperliquid = new FundingRateQuote(Exchange.HYPERLIQUID, "BTCUSDT",
                new BigDecimal("0.00015"), 1, new BigDecimal("100"), Instant.now());

        FundingRateSpread result = FundingRateSpreadCalculator.candidate(cex, hyperliquid,
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertEquals(Exchange.OKX, result.longLeg().exchange());
        assertEquals(0, new BigDecimal("0.0004").compareTo(result.fundingSpread()));
    }

    @Test
    void projectsRawFundingBySettlementCountsAcrossCommonWindow() {
        Instant now = Instant.parse("2026-09-04T00:00:00Z");

        BigDecimal projected = FundingRateYieldProjection.projectedSpread(
                new BigDecimal("0.0008"), 8, now.plus(Duration.ofHours(8)),
                new BigDecimal("0.0007"), 6, now.plus(Duration.ofHours(6)), now);

        assertEquals(0, new BigDecimal("0.0004").compareTo(projected));
    }

    @Test
    void excludesSettlementAtOpenTimeFromTimeAwareProjection() {
        Instant now = Instant.parse("2026-09-04T00:00:00Z");

        BigDecimal projected = FundingRateYieldProjection.projectedSpread(
                new BigDecimal("0.0008"), 8, now,
                new BigDecimal("0.00015"), 1, now.plus(Duration.ofHours(1)), now);

        assertEquals(0, new BigDecimal("0.0004").compareTo(projected));
    }
}
