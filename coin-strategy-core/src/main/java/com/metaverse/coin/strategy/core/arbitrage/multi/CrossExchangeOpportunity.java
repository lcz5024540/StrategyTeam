package com.metaverse.coin.strategy.core.arbitrage.multi;

import java.math.BigDecimal;
import java.time.Instant;

public record CrossExchangeOpportunity(String opportunityId, String symbol,
        NormalizedFundingRateSnapshot longQuote, NormalizedFundingRateSnapshot shortQuote,
        BigDecimal hourlyFundingSpread, BigDecimal estimatedNetYield,
        BigDecimal longEntryPrice, BigDecimal shortEntryPrice,
        BigDecimal executableNotional, Exchange firstLegExchange, Status status,
        String rejectionReason, Instant discoveredAt) {
    public enum Status { DISCOVERED, QUALIFIED, APPROVED, EXPIRED, REJECTED }
    public CrossExchangeOpportunity(String opportunityId, String symbol,
            NormalizedFundingRateSnapshot longQuote, NormalizedFundingRateSnapshot shortQuote,
            BigDecimal hourlyFundingSpread, BigDecimal estimatedNetYield,
            BigDecimal executableNotional, Exchange firstLegExchange, Status status,
            String rejectionReason, Instant discoveredAt) {
        this(opportunityId, symbol, longQuote, shortQuote, hourlyFundingSpread,
                estimatedNetYield, longQuote.markPrice(), shortQuote.markPrice(),
                executableNotional, firstLegExchange, status, rejectionReason, discoveredAt);
    }
    public CrossExchangeOpportunity(String opportunityId, String symbol,
            NormalizedFundingRateSnapshot longQuote, NormalizedFundingRateSnapshot shortQuote,
            BigDecimal hourlyFundingSpread, BigDecimal estimatedNetYield,
            BigDecimal executableNotional, Status status, String rejectionReason,
            Instant discoveredAt) {
        this(opportunityId, symbol, longQuote, shortQuote, hourlyFundingSpread,
                estimatedNetYield, executableNotional, longQuote.exchange(), status,
                rejectionReason, discoveredAt);
    }
    public boolean executable() { return status == Status.QUALIFIED || status == Status.APPROVED; }
}
