package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.time.Instant;
import java.util.List;

/** Fail-closed placeholder until an explicitly enabled private-account adapter is installed. */
public final class DisabledPrivatePerpetualAccountGateway implements PrivatePerpetualAccountGateway {
    private final Exchange exchange;

    public DisabledPrivatePerpetualAccountGateway(Exchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public Exchange exchange() {
        return exchange;
    }

    @Override
    public AccountSnapshot account() {
        throw unavailable();
    }

    @Override
    public List<PrivatePosition> positions() {
        throw unavailable();
    }

    @Override
    public List<FundingBill> fundingBills(Instant fromInclusive, Instant toExclusive) {
        throw unavailable();
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("private account access is disabled for " + exchange);
    }
}
