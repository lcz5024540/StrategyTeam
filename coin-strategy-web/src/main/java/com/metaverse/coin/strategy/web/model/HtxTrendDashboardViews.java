package com.metaverse.coin.strategy.web.model;

import java.math.BigDecimal;
import java.util.List;

public final class HtxTrendDashboardViews {
    private HtxTrendDashboardViews() { }

    public record Dashboard(String asOf, String source, Summary summary, List<Position> activePositions,
                            List<Position> recentPositions, List<DailyPnl> dailyPnl) { }
    public record Summary(long totalPositions, long activePositions, long closedPositions, long winningPositions,
                          BigDecimal totalTurnover, BigDecimal totalFees, BigDecimal realizedProfit,
                          BigDecimal netRealizedProfit) { }
    public record Position(long id, String contractCode, String status, String side, long contractVolume,
                           BigDecimal entryAvgPrice, BigDecimal closeAvgPrice, BigDecimal entryFee,
                           BigDecimal closeFee, BigDecimal realizedProfit, BigDecimal stopPrice,
                           BigDecimal trailingStopPrice, String entrySignal, String exitReason,
                           String createdAt, String updatedAt) { }
    public record DailyPnl(String tradeDate, long closedPositions, BigDecimal realizedProfit,
                           BigDecimal fees, BigDecimal netProfit) { }
}
