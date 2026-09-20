package com.metaverse.coin.strategy.web.service.strategy;

import com.metaverse.coin.strategy.web.mapper.HtxTrendDashboardMapper;
import com.metaverse.coin.strategy.web.model.HtxTrendDashboardViews;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Deliberately read-only dashboard projection; it never calls a trading endpoint. */
@Service
public class HtxTrendDashboardQueryService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final HtxTrendDashboardMapper mapper;

    public HtxTrendDashboardQueryService(HtxTrendDashboardMapper mapper) {
        this.mapper = mapper;
    }

    public HtxTrendDashboardViews.Dashboard dashboard() {
        try {
            return new HtxTrendDashboardViews.Dashboard(TIME.format(Instant.now()), "HTX_TREND_EXECUTION_LEDGER",
                    summary(mapper.summary()), runtime(mapper.runtime()),
                    mapper.activePositions().stream().map(this::position).toList(),
                    mapper.recentPositions().stream().map(this::position).toList(),
                    mapper.dailyPnl().stream().map(this::daily).toList(),
                    mapper.sidePnl().stream().map(this::dimensionPnl).toList(),
                    mapper.todaySidePnl().stream().map(this::dimensionPnl).toList(),
                    mapper.exitReasonPnl().stream().map(this::dimensionPnl).toList(),
                    mapper.recentOrders().stream().map(this::order).toList(),
                    mapper.recentSignals().stream().map(this::signal).toList(),
                    mapper.signalRollup().stream().map(this::signalRollup).toList(),
                    mapper.recentCandidateSignals().stream().map(this::candidateSignal).toList());
        } catch (DataAccessException exception) {
            return unavailableDashboard();
        }
    }

    private HtxTrendDashboardViews.Dashboard unavailableDashboard() {
        return new HtxTrendDashboardViews.Dashboard(TIME.format(Instant.now()), "HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE",
                new HtxTrendDashboardViews.Summary(0, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new HtxTrendDashboardViews.RuntimeSnapshot(null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private HtxTrendDashboardViews.Summary summary(Map<String, Object> row) {
        return new HtxTrendDashboardViews.Summary(number(row, "totalPositions").longValue(),
                number(row, "activePositions").longValue(), number(row, "closedPositions").longValue(),
                number(row, "winningPositions").longValue(), number(row, "todayClosedPositions").longValue(),
                number(row, "todayWinningPositions").longValue(), decimal(row, "totalTurnover"),
                decimal(row, "todayTurnover"), decimal(row, "totalFees"), decimal(row, "todayFees"),
                decimal(row, "realizedProfit"), decimal(row, "todayRealizedProfit"),
                decimal(row, "netRealizedProfit"), decimal(row, "todayNetRealizedProfit"));
    }

    private HtxTrendDashboardViews.RuntimeSnapshot runtime(Map<String, Object> row) {
        return new HtxTrendDashboardViews.RuntimeSnapshot(text(row, "latestSignalAt"), text(row, "latestOrderAt"),
                text(row, "latestPositionAt"), text(row, "latestCandidateAt"),
                number(row, "signalsLast30m").longValue(), number(row, "scannedContractsLast30m").longValue(),
                number(row, "longSignalsLast30m").longValue(), number(row, "shortSignalsLast30m").longValue(),
                number(row, "flatSignalsLast30m").longValue(), number(row, "candidateSignalsLast30m").longValue(),
                number(row, "candidateLongLast30m").longValue(), number(row, "candidateShortLast30m").longValue(),
                number(row, "candidateFlatLast30m").longValue());
    }

    private HtxTrendDashboardViews.Position position(Map<String, Object> row) {
        return new HtxTrendDashboardViews.Position(number(row, "id").longValue(), text(row, "contractCode"),
                text(row, "status"), text(row, "side"), number(row, "contractVolume").longValue(),
                decimal(row, "entryFilledContracts"), decimal(row, "closeFilledContracts"),
                nullableDecimal(row, "entryAvgPrice"), nullableDecimal(row, "closeAvgPrice"),
                decimal(row, "entryFee"), decimal(row, "closeFee"), nullableDecimal(row, "realizedProfit"),
                nullableDecimal(row, "netRealizedProfit"), nullableDecimal(row, "turnover"),
                nullableDecimal(row, "stopPrice"), nullableDecimal(row, "trailingStopPrice"),
                text(row, "entrySignal"), text(row, "exitReason"), text(row, "message"),
                text(row, "accountLabel"), nullableDecimal(row, "entryFeeRate"),
                nullableDecimal(row, "estimatedEntryFee"), nullableDecimal(row, "estimatedCloseFee"),
                nullableDecimal(row, "expectedNetAfterFees"), nullableDecimal(row, "sizingMultiplier"),
                text(row, "sizingReason"), text(row, "entryGateReason"), text(row, "createdAt"), text(row, "updatedAt"));
    }

    private HtxTrendDashboardViews.DailyPnl daily(Map<String, Object> row) {
        return new HtxTrendDashboardViews.DailyPnl(text(row, "tradeDate"), number(row, "closedPositions").longValue(),
                decimal(row, "realizedProfit"), decimal(row, "fees"), decimal(row, "netProfit"), decimal(row, "turnover"));
    }

    private HtxTrendDashboardViews.DimensionPnl dimensionPnl(Map<String, Object> row) {
        return new HtxTrendDashboardViews.DimensionPnl(text(row, "bucket"), text(row, "side"),
                number(row, "closedPositions").longValue(), number(row, "winningPositions").longValue(),
                decimal(row, "realizedProfit"), decimal(row, "fees"), decimal(row, "netProfit"), decimal(row, "turnover"));
    }

    private HtxTrendDashboardViews.TrendOrder order(Map<String, Object> row) {
        return new HtxTrendDashboardViews.TrendOrder(number(row, "id").longValue(), number(row, "positionId").longValue(),
                text(row, "contractCode"), text(row, "leg"), text(row, "trendSide"), text(row, "orderSide"),
                text(row, "positionSide"), bool(row, "reduceOnly"), text(row, "clientOrderId"),
                nullableLong(row, "exchangeOrderId"), number(row, "requestVolume").longValue(),
                decimal(row, "filledContracts"), nullableDecimal(row, "averagePrice"), decimal(row, "fee"),
                nullableDecimal(row, "realizedProfit"), nullableDecimal(row, "notional"), text(row, "exchangeState"),
                text(row, "accountLabel"), nullableDecimal(row, "feeRateUsed"), nullableDecimal(row, "estimatedFee"),
                nullableDecimal(row, "estimatedOpenFee"), nullableDecimal(row, "estimatedCloseFee"),
                nullableDecimal(row, "sizingMultiplier"), text(row, "sizingReason"), text(row, "entryGateReason"),
                text(row, "status"), text(row, "reason"), text(row, "message"), text(row, "createdAt"), text(row, "updatedAt"));
    }

    private HtxTrendDashboardViews.TrendSignal signal(Map<String, Object> row) {
        return new HtxTrendDashboardViews.TrendSignal(number(row, "id").longValue(), text(row, "contractCode"),
                text(row, "period"), nullableLong(row, "barTime"), text(row, "trendSignal"),
                nullableDecimal(row, "closePrice"), nullableDecimal(row, "emaFast"), nullableDecimal(row, "emaSlow"),
                nullableDecimal(row, "breakoutHigh"), nullableDecimal(row, "breakoutLow"), nullableDecimal(row, "atr"),
                nullableDecimal(row, "atrPct"), nullableDecimal(row, "adx"), nullableDecimal(row, "spreadRate"),
                nullableDecimal(row, "fundingRate"), text(row, "reason"), text(row, "createdAt"));
    }

    private HtxTrendDashboardViews.SignalRollup signalRollup(Map<String, Object> row) {
        return new HtxTrendDashboardViews.SignalRollup(text(row, "trendSignal"), text(row, "reasonBucket"),
                number(row, "signals").longValue(), number(row, "contracts").longValue(),
                decimal(row, "avgAdx"), decimal(row, "avgAtrPct"), text(row, "latestAt"));
    }

    private HtxTrendDashboardViews.CandidateSignal candidateSignal(Map<String, Object> row) {
        return new HtxTrendDashboardViews.CandidateSignal(number(row, "id").longValue(), text(row, "contractCode"),
                text(row, "period"), nullableLong(row, "barTime"), text(row, "direction"), text(row, "regime"),
                decimal(row, "signalScore"), decimal(row, "longScore"), decimal(row, "shortScore"),
                decimal(row, "scoreGap"), nullableDecimal(row, "closePrice"), nullableDecimal(row, "atrPct"),
                nullableDecimal(row, "adx"), nullableDecimal(row, "momentumPct"), nullableDecimal(row, "volumeRatio"),
                text(row, "entryReason"), text(row, "invalidationReason"), text(row, "modelVersion"), text(row, "createdAt"));
    }

    private static Number number(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number : 0;
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        BigDecimal value = nullableDecimal(row, key);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal nullableDecimal(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    private static Long nullableLong(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static boolean bool(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof Number number && number.longValue() != 0L;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }
}
