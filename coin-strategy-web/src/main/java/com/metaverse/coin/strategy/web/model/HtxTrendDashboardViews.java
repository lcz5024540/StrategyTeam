package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.List;

public final class HtxTrendDashboardViews {
    private HtxTrendDashboardViews() {
    }

    public record Dashboard(String asOf, String source, Summary summary, LedgerSummary ledgerSummary,
                            PerformanceSummary performance, RuntimeSnapshot runtime,
                            List<AccountSummary> accountSummaries, List<Position> activePositions,
                            List<Position> recentPositions, List<DailyPnl> dailyPnl, List<DimensionPnl> sidePnl,
                            List<DimensionPnl> todaySidePnl, List<DimensionPnl> exitReasonPnl,
                            List<DimensionPnl> entryModelPnl, List<DimensionPnl> marketRegimePnl,
                            List<DimensionPnl> symbolPnl, List<TrendOrder> recentOrders,
                            List<LedgerEvent> recentLedgerEvents,
                            List<TrendSignal> recentSignals, List<SignalRollup> signalRollup,
                            List<CandidateSignal> recentCandidateSignals) {
    }

    public record Summary(long totalPositions, long activePositions, long closedPositions, long winningPositions,
                          long todayClosedPositions, long todayWinningPositions, BigDecimal totalTurnover,
                          BigDecimal todayTurnover, BigDecimal totalFees, BigDecimal todayFees,
                          BigDecimal realizedProfit, BigDecimal todayRealizedProfit,
                          BigDecimal netRealizedProfit, BigDecimal todayNetRealizedProfit) {
    }

    public record LedgerSummary(long ledgerEventCount, BigDecimal ledgerTotalTurnover, BigDecimal ledgerTodayTurnover,
                                BigDecimal ledgerTotalFees, BigDecimal ledgerTodayFees,
                                BigDecimal ledgerNetRealizedProfit, BigDecimal ledgerTodayNetRealizedProfit,
                                String latestLedgerAt) {
    }

    public record PerformanceSummary(long closedPositions, long winningPositions, long losingPositions,
                                     BigDecimal winRate, BigDecimal averageWin, BigDecimal averageLoss,
                                     BigDecimal payoffRatio, BigDecimal profitFactor, BigDecimal expectancy,
                                     BigDecimal netRealizedProfit, long todayClosedPositions,
                                     long todayWinningPositions, long todayLosingPositions, BigDecimal todayWinRate,
                                     BigDecimal todayAverageWin, BigDecimal todayAverageLoss,
                                     BigDecimal todayPayoffRatio, BigDecimal todayProfitFactor,
                                     BigDecimal todayExpectancy, BigDecimal todayNetRealizedProfit) {
    }

    public record RuntimeSnapshot(String latestSignalAt, String latestOrderAt, String latestPositionAt,
                                  String latestCandidateAt, long signalsLast30m, long scannedContractsLast30m,
                                  long longSignalsLast30m, long shortSignalsLast30m, long flatSignalsLast30m,
                                  long candidateSignalsLast30m, long candidateLongLast30m,
                                  long candidateShortLast30m, long candidateFlatLast30m) {
    }

    public record AccountSummary(String accountLabel, String accountRole, boolean accountEnabled,
                                 boolean trendEnabled, boolean mirrorEnabled, long totalPositions, long activePositions,
                                 long closedPositions, long winningPositions, long todayClosedPositions,
                                 long todayWinningPositions, BigDecimal totalTurnover, BigDecimal todayTurnover,
                                 BigDecimal totalFees, BigDecimal todayFees, BigDecimal realizedProfit,
                                 BigDecimal todayRealizedProfit, BigDecimal netRealizedProfit,
                                 BigDecimal todayNetRealizedProfit, long totalOrders, long todayOrders,
                                 long openOrders, long entryOrders, long closeOrders, BigDecimal orderNotional,
                                 BigDecimal todayOrderNotional, BigDecimal orderFees, BigDecimal todayOrderFees,
                                 BigDecimal estimatedOrderFees, BigDecimal todayEstimatedOrderFees,
                                 BigDecimal orderRealizedProfit, BigDecimal todayOrderRealizedProfit,
                                 long ledgerEventCount, BigDecimal ledgerTotalTurnover, BigDecimal ledgerTodayTurnover,
                                 BigDecimal ledgerTotalFees, BigDecimal ledgerTodayFees,
                                 BigDecimal ledgerNetRealizedProfit, BigDecimal ledgerTodayNetRealizedProfit,
                                 String latestLedgerAt, String latestOrderAt, String latestPositionAt) {
    }

    public record Position(long id, String contractCode, String status, String side, long contractVolume,
                           BigDecimal entryFilledContracts, BigDecimal closeFilledContracts,
                           BigDecimal entryAvgPrice, BigDecimal closeAvgPrice, BigDecimal entryFee,
                           BigDecimal closeFee, BigDecimal realizedProfit, BigDecimal netRealizedProfit,
                           BigDecimal turnover, BigDecimal stopPrice, BigDecimal trailingStopPrice,
                           String entrySignal, String exitReason, String message, String accountLabel,
                           BigDecimal entryFeeRate, BigDecimal estimatedEntryFee, BigDecimal estimatedCloseFee,
                           BigDecimal expectedNetAfterFees, BigDecimal sizingMultiplier, String sizingReason,
                           String entryGateReason, String exitSnapshotState, BigDecimal exitSnapshotNet,
                           BigDecimal exitSnapshotMfe, BigDecimal exitSnapshotMae, BigDecimal exitSnapshotGiveback,
                           Long exitSnapshotBars, String exitSnapshotCandidates, String exitSnapshotUpdatedAt,
                           String createdAt, String updatedAt) {
    }

    public record DailyPnl(String tradeDate, long closedPositions, BigDecimal realizedProfit,
                           BigDecimal fees, BigDecimal netProfit, BigDecimal turnover) {
    }

    public record DimensionPnl(String bucket, String side, long closedPositions, long winningPositions,
                               long losingPositions, BigDecimal winRate, BigDecimal averageWin,
                               BigDecimal averageLoss, BigDecimal payoffRatio, BigDecimal profitFactor,
                               BigDecimal expectancy, BigDecimal realizedProfit, BigDecimal fees,
                               BigDecimal netProfit, BigDecimal turnover) {
    }

    public record TrendOrder(long id, long positionId, String contractCode, String leg, String trendSide,
                             String orderSide, String positionSide, boolean reduceOnly, String clientOrderId,
                             Long exchangeOrderId, long requestVolume, BigDecimal filledContracts,
                             BigDecimal averagePrice, BigDecimal fee, BigDecimal realizedProfit,
                             BigDecimal notional, String exchangeState, String accountLabel, BigDecimal feeRateUsed,
                             BigDecimal estimatedFee, BigDecimal estimatedOpenFee, BigDecimal estimatedCloseFee,
                             BigDecimal sizingMultiplier, String sizingReason, String entryGateReason,
                             String status, String reason, String message, String createdAt, String updatedAt) {
    }

    public record LedgerEvent(long id, String eventKey, String eventType, String accountLabel, String contractCode,
                              long positionId, Long orderId, String clientOrderId, String side,
                              BigDecimal executionPriceUsdt, BigDecimal entryPriceUsdt, BigDecimal closePriceUsdt,
                              BigDecimal filledContracts, BigDecimal contractSize, BigDecimal amountUsdt,
                              BigDecimal feeUsdt, BigDecimal grossPnlUsdt, BigDecimal netPnlAfterFee,
                              String reason, String occurredAt) {
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
