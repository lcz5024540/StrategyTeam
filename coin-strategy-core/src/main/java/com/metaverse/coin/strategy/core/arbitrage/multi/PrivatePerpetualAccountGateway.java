package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only private-account contract for the pre-live OKX/Bitget integration.
 * Implementations must not submit, cancel, or alter orders while live execution is disabled.
 */
public interface PrivatePerpetualAccountGateway {
    Exchange exchange();

    AccountSnapshot account();

    List<PrivatePosition> positions();

    List<FundingBill> fundingBills(Instant fromInclusive, Instant toExclusive);

    record AccountSnapshot(BigDecimal equity, BigDecimal usedMargin, BigDecimal availableMargin,
            BigDecimal maintenanceMargin, Instant observedAt) {
    }

    record PrivatePosition(String symbol, HedgeSide side, BigDecimal quantity,
            BigDecimal isolatedMargin, BigDecimal liquidationPrice, BigDecimal markPrice,
            Instant observedAt) {
    }

    record FundingBill(String billId, String symbol, BigDecimal amount, BigDecimal fundingRate,
            Instant settledAt) {
    }
}
