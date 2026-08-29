package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

public class CrossExchangeRiskService {
    public record Limits(BigDecimal maxSymbolNotional, BigDecimal maxExchangeNotional,
            Duration maxNakedDuration, Duration maxHoldingDuration, BigDecimal exitNetYield,
            BigDecimal maxMarkPriceDeviation) {
        public Limits(BigDecimal maxSymbolNotional, Duration maxNakedDuration,
                Duration maxHoldingDuration, BigDecimal exitNetYield) {
            this(maxSymbolNotional, maxSymbolNotional, maxNakedDuration, maxHoldingDuration,
                    exitNetYield, new BigDecimal("0.01"));
        }
        public Limits(BigDecimal maxSymbolNotional, BigDecimal maxExchangeNotional,
                Duration maxNakedDuration, Duration maxHoldingDuration, BigDecimal exitNetYield) {
            this(maxSymbolNotional, maxExchangeNotional, maxNakedDuration, maxHoldingDuration,
                    exitNetYield, new BigDecimal("0.01"));
        }
    }
    public record Assessment(boolean passed, String reason) { }

    private final Limits limits;
    public CrossExchangeRiskService(Limits limits) { this.limits = limits; }

    public Assessment approveOpen(CrossExchangeOpportunity opportunity, BigDecimal notional,
            Collection<PaperHedgePosition> positions, boolean paused, boolean circuitOpen) {
        if (paused) return new Assessment(false, "STRATEGY_PAUSED");
        if (circuitOpen) return new Assessment(false, "CIRCUIT_OPEN");
        if (opportunity == null) return new Assessment(false, "OPPORTUNITY_REQUIRED");
        if (notional == null) return new Assessment(false, "NOTIONAL_REQUIRED");
        if (!opportunity.executable()) return new Assessment(false, "OPPORTUNITY_NOT_EXECUTABLE");
        if (opportunity.executableNotional() == null
                || notional.compareTo(opportunity.executableNotional()) > 0) {
            return new Assessment(false, "OPPORTUNITY_NOTIONAL_LIMIT");
        }
        if (notional.signum() <= 0 || notional.compareTo(limits.maxSymbolNotional()) > 0) return new Assessment(false, "SYMBOL_NOTIONAL_LIMIT");
        BigDecimal longExchangeExposure = exchangeExposure(positions,
                opportunity.longQuote().exchange());
        BigDecimal shortExchangeExposure = exchangeExposure(positions,
                opportunity.shortQuote().exchange());
        if (longExchangeExposure.add(notional).compareTo(limits.maxExchangeNotional()) > 0
                || shortExchangeExposure.add(notional).compareTo(limits.maxExchangeNotional()) > 0) {
            return new Assessment(false, "EXCHANGE_NOTIONAL_LIMIT");
        }
        boolean duplicate = positions.stream().anyMatch(p -> p.status() == PaperHedgePosition.Status.OPENED
                && p.opportunity().symbol().equals(opportunity.symbol()));
        return duplicate ? new Assessment(false, "DUPLICATE_ACTIVE_SYMBOL") : new Assessment(true, null);
    }

    private BigDecimal exchangeExposure(Collection<PaperHedgePosition> positions, Exchange exchange) {
        return positions.stream()
                .filter(position -> position.status() == PaperHedgePosition.Status.OPENED)
                .filter(position -> position.opportunity().longQuote().exchange() == exchange
                        || position.opportunity().shortQuote().exchange() == exchange)
                .map(position -> position.hedgedQuantity().multiply(
                        position.opportunity().longQuote().exchange() == exchange
                                ? position.opportunity().longQuote().markPrice()
                                : position.opportunity().shortQuote().markPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public Assessment shouldExit(PaperHedgePosition position, BigDecimal latestNetYield, Instant now,
            boolean exchangeHealthy, boolean paused) {
        return shouldExit(position, latestNetYield, BigDecimal.ZERO, now, exchangeHealthy, paused);
    }

    public Assessment shouldExit(PaperHedgePosition position, BigDecimal latestNetYield,
            BigDecimal markPriceDeviation, Instant now, boolean exchangeHealthy, boolean paused) {
        return shouldExit(position, latestNetYield, markPriceDeviation, now, exchangeHealthy,
                paused, true);
    }

    public Assessment shouldExit(PaperHedgePosition position, BigDecimal latestNetYield,
            BigDecimal markPriceDeviation, Instant now, boolean exchangeHealthy, boolean paused,
            boolean settlementAligned) {
        if (paused) return new Assessment(true, "STRATEGY_PAUSED");
        if (!exchangeHealthy) return new Assessment(true, "EXCHANGE_UNHEALTHY");
        if (!settlementAligned) return new Assessment(true, "SETTLEMENT_TIME_SKEW");
        if (markPriceDeviation.compareTo(limits.maxMarkPriceDeviation()) > 0) {
            return new Assessment(true, "MARK_PRICE_DEVIATION");
        }
        if (latestNetYield.compareTo(limits.exitNetYield()) < 0) return new Assessment(true, "NET_YIELD_EXIT_THRESHOLD");
        if (position.openedAt() != null && position.openedAt().plus(limits.maxHoldingDuration()).isBefore(now)) return new Assessment(true, "MAX_HOLDING_DURATION");
        return new Assessment(false, null);
    }

    public Assessment nakedExposure(PaperHedgePosition position, Instant now) {
        if (position.status() != PaperHedgePosition.Status.MANUAL_INTERVENTION) {
            return new Assessment(false, null);
        }
        if (!position.updatedAt().plus(limits.maxNakedDuration()).isAfter(now)) {
            return new Assessment(true, "MAX_NAKED_DURATION");
        }
        return new Assessment(false, null);
    }
}
