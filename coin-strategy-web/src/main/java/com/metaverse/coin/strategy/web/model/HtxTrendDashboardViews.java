package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.List;

public final class HtxTrendDashboardViews {
    private HtxTrendDashboardViews() {
    }

    public record Dashboard(String asOf, String source, Summary summary, RuntimeSnapshot runtime,
                            List<Position> activePositions, List<Position> recentPositions,
                            List<DailyPnl> dailyPnl, List<DimensionPnl> sidePnl,
                            List<DimensionPnl> todaySidePnl, List<DimensionPnl> exitReasonPnl,
                            List<TrendOrder> recentOrders, List<TrendSignal> recentSignals,
                            List<SignalRollup> signalRollup, List<CandidateSignal> recentCandidateSignals) {
    }

    public record Summary(long totalPositions, long activePositions, long closedPositions, long winningPositions,
                          long todayClosedPositions, long todayWinningPositions, BigDecimal totalTurnover,
                          BigDecimal todayTurnover, BigDecimal totalFees, BigDecimal todayFees,
                          BigDecimal realizedProfit, BigDecimal todayRealizedProfit,
                          BigDecimal netRealizedProfit, BigDecimal todayNetRealizedProfit) {
    }

    public record RuntimeSnapshot(String latestSignalAt, String latestOrderAt, String latestPositionAt,
                                  String latestCandidateAt, long signalsLast30m, long scannedContractsLast30m,
                                  long longSignalsLast30m, long shortSignalsLast30m, long flatSignalsLast30m,
                                  long candidateSignalsLast30m, long candidateLongLast30m,
                                  long candidateShortLast30m, long candidateFlatLast30m) {
    }

    public record Position(long id, String contractCode, String status, String side, long contractVolume,
                           BigDecimal entryFilledContracts, BigDecimal closeFilledContracts,
                           BigDecimal entryAvgPrice, BigDecimal closeAvgPrice, BigDecimal entryFee,
                           BigDecimal closeFee, BigDecimal realizedProfit, BigDecimal netRealizedProfit,
                           BigDecimal turnover, BigDecimal stopPrice, BigDecimal trailingStopPrice,
                           String entrySignal, String exitReason, String message, String createdAt,
                           String updatedAt) {
    }

    public record DailyPnl(String tradeDate, long closedPositions, BigDecimal realizedProfit,
                           BigDecimal fees, BigDecimal netProfit, BigDecimal turnover) {
    }

    public record DimensionPnl(String bucket, String side, long closedPositions, long winningPositions,
                               BigDecimal realizedProfit, BigDecimal fees, BigDecimal netProfit,
                               BigDecimal turnover) {
    }

    public record TrendOrder(long id, long positionId, String contractCode, String leg, String trendSide,
                             String orderSide, String positionSide, boolean reduceOnly, String clientOrderId,
                             Long exchangeOrderId, long requestVolume, BigDecimal filledContracts,
                             BigDecimal averagePrice, BigDecimal fee, BigDecimal realizedProfit,
                             BigDecimal notional, String exchangeState, String status, String reason,
                             String message, String createdAt, String updatedAt) {
    }

    public record TrendSignal(long id, String contractCode, String period, Long barTime, String trendSignal,
                              BigDecimal closePrice, BigDecimal emaFast, BigDecimal emaSlow,
                              BigDecimal breakoutHigh, BigDecimal breakoutLow, BigDecimal atr,
                              BigDecimal atrPct, BigDecimal adx, BigDecimal spreadRate,
                              BigDecimal fundingRate, String reason, String createdAt) {
    }

    public record SignalRollup(String trendSignal, String reasonBucket, long signals, long contracts,
                               BigDecimal avgAdx, BigDecimal avgAtrPct, String latestAt) {
    }

    public record CandidateSignal(long id, String contractCode, String period, Long barTime, String direction,
                                  String regime, BigDecimal signalScore, BigDecimal longScore,
                                  BigDecimal shortScore, BigDecimal scoreGap, BigDecimal closePrice,
                                  BigDecimal atrPct, BigDecimal adx, BigDecimal momentumPct,
                                  BigDecimal volumeRatio, String entryReason, String invalidationReason,
                                  String modelVersion, String createdAt) {
    }
}
