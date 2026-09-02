package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.List;

public final class ArbitrageDashboardViews {

    private ArbitrageDashboardViews() {
    }

    public record Envelope<T>(String asOf, String timezone, String source, boolean partial,
                              List<String> missingExchanges, T data) {
    }

    public record Overview(long executableOpportunities, long openPositions, BigDecimal executableNotional,
                           BigDecimal expectedNetYield, BigDecimal fundingPnl, BigDecimal netDelta,
                           String riskState, long evaluatedDirections, long rejectedDirections,
                           BigDecimal paperCapitalPerExchange, BigDecimal maximumPaperLeverage,
                           BigDecimal maximumNotionalPerExchange) {
    }

    public record Opportunity(Long id, String opportunityCode, String kind, String symbol,
                              String longExchange, String shortExchange, BigDecimal longFundingRate,
                              BigDecimal shortFundingRate, BigDecimal fundingSpread,
                              BigDecimal estimatedNetYield, BigDecimal executableNotional,
                              String status, String rejectionReason, String discoveredAt,
                              String updatedAt, BigDecimal longVwap, BigDecimal shortVwap,
                              BigDecimal executableBasis, Long quoteAgeMs, BigDecimal longVolume24h,
                              BigDecimal shortVolume24h, BigDecimal longOpenInterest,
                              BigDecimal shortOpenInterest, BigDecimal funding24h,
                              BigDecimal funding7d, BigDecimal funding30d) {
    }

    public record OpportunityPage(List<Opportunity> items, long total, int page, int size,
                                  long qualified, long rejected, long stale) {
    }

    public record Position(String positionCode, String kind, String symbol, String longExchange,
                           String shortExchange, BigDecimal longQuantity, BigDecimal shortQuantity,
                           BigDecimal netDelta, BigDecimal notional, BigDecimal fundingPnl,
                           BigDecimal basisPnl, String status, String nextSettlementAt,
                           String openedAt, String message) {
    }

    public record TimePoint(String bucketAt, BigDecimal fundingPnl, BigDecimal tradingCashflowPnl,
                            BigDecimal fee, BigDecimal netPnl, BigDecimal cumulativeNetPnl) {
    }

    public record MarketHealth(String exchange, String status, BigDecimal successRate,
                               Long p50LatencyMs, Long p95LatencyMs, Long quoteAgeMs,
                               BigDecimal l2Completeness, String lastError) {
    }

    public record DataQuality(long discoveredContracts, long theoreticalDirections,
                              long displayedDirections, long qualifiedDirections,
                              long missingL2, long staleQuotes, long unavailableExchangeDirections,
                              List<ReasonCount> exclusionReasons) {
    }

    public record ReasonCount(String reason, long count) {
    }
}
