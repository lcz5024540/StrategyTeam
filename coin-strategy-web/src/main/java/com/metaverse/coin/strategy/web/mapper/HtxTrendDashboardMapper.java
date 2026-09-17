package com.metaverse.coin.strategy.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** Read-only SQL projection of the HTX trend execution ledger. */
@Mapper
public interface HtxTrendDashboardMapper {

    String TREND_SCHEMA = "htx_trade_strategy.";
    String POSITION_TABLE = TREND_SCHEMA + "htx_trend_position";
    String ORDER_TABLE = TREND_SCHEMA + "htx_trend_order";
    String SIGNAL_TABLE = TREND_SCHEMA + "htx_trend_signal";
    String CANDIDATE_SIGNAL_TABLE = TREND_SCHEMA + "htx_trend_candidate_signal";

    String CLOSED_TURNOVER = "entry_filled_contracts * contract_size * entry_avg_price "
            + "+ close_filled_contracts * contract_size * close_avg_price";

    @Select("SELECT COUNT(*) AS totalPositions, "
            + "SUM(CASE WHEN status IN ('INTENT','ENTRY_SENT','ACTIVE','CLOSE_SENT','UNKNOWN') THEN 1 ELSE 0 END) AS activePositions, "
            + "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS closedPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND realized_profit > 0 THEN 1 ELSE 0 END) AS winningPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() THEN 1 ELSE 0 END) AS todayClosedPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() AND realized_profit > 0 THEN 1 ELSE 0 END) AS todayWinningPositions, "
            + "COALESCE(SUM(" + CLOSED_TURNOVER + "), 0) AS totalTurnover, "
            + "COALESCE(SUM(CASE WHEN updated_at >= CURDATE() THEN " + CLOSED_TURNOVER + " ELSE 0 END), 0) AS todayTurnover, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS totalFees, "
            + "COALESCE(SUM(CASE WHEN updated_at >= CURDATE() THEN entry_fee + close_fee ELSE 0 END), 0) AS todayFees, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' THEN realized_profit ELSE 0 END), 0) AS realizedProfit, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() THEN realized_profit ELSE 0 END), 0) AS todayRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' THEN realized_profit - entry_fee - close_fee ELSE 0 END), 0) AS netRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() THEN realized_profit - entry_fee - close_fee ELSE 0 END), 0) AS todayNetRealizedProfit "
            + "FROM " + POSITION_TABLE + "")
    Map<String, Object> summary();

    @Select("SELECT "
            + "(SELECT MAX(created_at) FROM " + SIGNAL_TABLE + ") AS latestSignalAt, "
            + "(SELECT MAX(updated_at) FROM " + ORDER_TABLE + ") AS latestOrderAt, "
            + "(SELECT MAX(updated_at) FROM " + POSITION_TABLE + ") AS latestPositionAt, "
            + "(SELECT MAX(created_at) FROM " + CANDIDATE_SIGNAL_TABLE + ") AS latestCandidateAt, "
            + "(SELECT COUNT(*) FROM " + SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE) AS signalsLast30m, "
            + "(SELECT COUNT(DISTINCT contract_code) FROM " + SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE) AS scannedContractsLast30m, "
            + "(SELECT COUNT(*) FROM " + SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE AND trend_signal = 'LONG') AS longSignalsLast30m, "
            + "(SELECT COUNT(*) FROM " + SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE AND trend_signal = 'SHORT') AS shortSignalsLast30m, "
            + "(SELECT COUNT(*) FROM " + SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE AND trend_signal = 'FLAT') AS flatSignalsLast30m, "
            + "(SELECT COUNT(*) FROM " + CANDIDATE_SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE) AS candidateSignalsLast30m, "
            + "(SELECT COUNT(*) FROM " + CANDIDATE_SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE AND direction = 'LONG') AS candidateLongLast30m, "
            + "(SELECT COUNT(*) FROM " + CANDIDATE_SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE AND direction = 'SHORT') AS candidateShortLast30m, "
            + "(SELECT COUNT(*) FROM " + CANDIDATE_SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 30 MINUTE AND direction = 'FLAT') AS candidateFlatLast30m")
    Map<String, Object> runtime();

    @Select("SELECT id, contract_code AS contractCode, status, side, contract_volume AS contractVolume, "
            + "entry_filled_contracts AS entryFilledContracts, close_filled_contracts AS closeFilledContracts, "
            + "entry_avg_price AS entryAvgPrice, close_avg_price AS closeAvgPrice, entry_fee AS entryFee, close_fee AS closeFee, "
            + "realized_profit AS realizedProfit, realized_profit - entry_fee - close_fee AS netRealizedProfit, "
            + "(" + CLOSED_TURNOVER + ") AS turnover, stop_price AS stopPrice, trailing_stop_price AS trailingStopPrice, "
            + "entry_signal AS entrySignal, exit_reason AS exitReason, message, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM " + POSITION_TABLE + " WHERE status IN ('INTENT','ENTRY_SENT','ACTIVE','CLOSE_SENT','UNKNOWN') "
            + "ORDER BY updated_at DESC, id DESC LIMIT 50")
    List<Map<String, Object>> activePositions();

    @Select("SELECT id, contract_code AS contractCode, status, side, contract_volume AS contractVolume, "
            + "entry_filled_contracts AS entryFilledContracts, close_filled_contracts AS closeFilledContracts, "
            + "entry_avg_price AS entryAvgPrice, close_avg_price AS closeAvgPrice, entry_fee AS entryFee, close_fee AS closeFee, "
            + "realized_profit AS realizedProfit, realized_profit - entry_fee - close_fee AS netRealizedProfit, "
            + "(" + CLOSED_TURNOVER + ") AS turnover, stop_price AS stopPrice, trailing_stop_price AS trailingStopPrice, "
            + "entry_signal AS entrySignal, exit_reason AS exitReason, message, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM " + POSITION_TABLE + " ORDER BY updated_at DESC, id DESC LIMIT 100")
    List<Map<String, Object>> recentPositions();

    @Select("SELECT DATE(updated_at) AS tradeDate, COUNT(*) AS closedPositions, "
            + "COALESCE(SUM(realized_profit), 0) AS realizedProfit, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS fees, "
            + "COALESCE(SUM(realized_profit - entry_fee - close_fee), 0) AS netProfit, "
            + "COALESCE(SUM(" + CLOSED_TURNOVER + "), 0) AS turnover "
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' GROUP BY DATE(updated_at) "
            + "ORDER BY tradeDate DESC LIMIT 30")
    List<Map<String, Object>> dailyPnl();

    @Select("SELECT side AS bucket, side, COUNT(*) AS closedPositions, "
            + "SUM(CASE WHEN realized_profit > 0 THEN 1 ELSE 0 END) AS winningPositions, "
            + "COALESCE(SUM(realized_profit), 0) AS realizedProfit, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS fees, "
            + "COALESCE(SUM(realized_profit - entry_fee - close_fee), 0) AS netProfit, "
            + "COALESCE(SUM(" + CLOSED_TURNOVER + "), 0) AS turnover "
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' GROUP BY side ORDER BY netProfit DESC")
    List<Map<String, Object>> sidePnl();

    @Select("SELECT side AS bucket, side, COUNT(*) AS closedPositions, "
            + "SUM(CASE WHEN realized_profit > 0 THEN 1 ELSE 0 END) AS winningPositions, "
            + "COALESCE(SUM(realized_profit), 0) AS realizedProfit, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS fees, "
            + "COALESCE(SUM(realized_profit - entry_fee - close_fee), 0) AS netProfit, "
            + "COALESCE(SUM(" + CLOSED_TURNOVER + "), 0) AS turnover "
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' AND updated_at >= CURDATE() "
            + "GROUP BY side ORDER BY netProfit DESC")
    List<Map<String, Object>> todaySidePnl();

    @Select("SELECT CASE "
            + "WHEN exit_reason LIKE '%loss control%' THEN 'flat loss control' "
            + "WHEN exit_reason LIKE '%fee protection%' THEN 'flat after fee protection' "
            + "WHEN exit_reason LIKE '%trailing stop%' THEN 'trailing stop' "
            + "WHEN exit_reason LIKE '%take profit%' THEN 'take profit' "
            + "WHEN exit_reason IS NULL OR exit_reason = '' THEN 'unknown' "
            + "ELSE LEFT(exit_reason, 96) END AS bucket, side, COUNT(*) AS closedPositions, "
            + "SUM(CASE WHEN realized_profit > 0 THEN 1 ELSE 0 END) AS winningPositions, "
            + "COALESCE(SUM(realized_profit), 0) AS realizedProfit, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS fees, "
            + "COALESCE(SUM(realized_profit - entry_fee - close_fee), 0) AS netProfit, "
            + "COALESCE(SUM(" + CLOSED_TURNOVER + "), 0) AS turnover "
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' AND updated_at >= CURDATE() "
            + "GROUP BY bucket, side ORDER BY netProfit ASC LIMIT 20")
    List<Map<String, Object>> exitReasonPnl();

    @Select("SELECT o.id, o.position_id AS positionId, o.contract_code AS contractCode, o.leg, o.trend_side AS trendSide, "
            + "o.order_side AS orderSide, o.position_side AS positionSide, o.reduce_only AS reduceOnly, "
            + "o.client_order_id AS clientOrderId, o.exchange_order_id AS exchangeOrderId, o.request_volume AS requestVolume, "
            + "o.filled_contracts AS filledContracts, o.average_price AS averagePrice, o.fee, o.realized_profit AS realizedProfit, "
            + "o.filled_contracts * p.contract_size * o.average_price AS notional, o.exchange_state AS exchangeState, "
            + "o.status, o.reason, o.message, o.created_at AS createdAt, o.updated_at AS updatedAt "
            + "FROM " + ORDER_TABLE + " o LEFT JOIN " + POSITION_TABLE + " p ON p.id = o.position_id "
            + "ORDER BY o.updated_at DESC, o.id DESC LIMIT 100")
    List<Map<String, Object>> recentOrders();

    @Select("SELECT id, contract_code AS contractCode, period, bar_time AS barTime, trend_signal AS trendSignal, "
            + "close_price AS closePrice, ema_fast AS emaFast, ema_slow AS emaSlow, breakout_high AS breakoutHigh, "
            + "breakout_low AS breakoutLow, atr, atr_pct AS atrPct, adx, spread_rate AS spreadRate, "
            + "funding_rate AS fundingRate, reason, created_at AS createdAt "
            + "FROM " + SIGNAL_TABLE + " ORDER BY created_at DESC, id DESC LIMIT 120")
    List<Map<String, Object>> recentSignals();

    @Select("SELECT trend_signal AS trendSignal, CASE "
            + "WHEN reason LIKE 'ADX below threshold%' THEN 'ADX below threshold' "
            + "WHEN reason LIKE 'no breakout aligned%' THEN 'no breakout aligned' "
            + "WHEN reason LIKE 'ATR below threshold%' THEN 'ATR below threshold' "
            + "WHEN reason LIKE 'entry kline unavailable%' THEN 'entry kline unavailable' "
            + "WHEN reason LIKE 'trend kline unavailable%' THEN 'trend kline unavailable' "
            + "WHEN reason IS NULL OR reason = '' THEN 'none' ELSE LEFT(reason, 96) END AS reasonBucket, "
            + "COUNT(*) AS signals, COUNT(DISTINCT contract_code) AS contracts, COALESCE(AVG(adx), 0) AS avgAdx, "
            + "COALESCE(AVG(atr_pct), 0) AS avgAtrPct, MAX(created_at) AS latestAt "
            + "FROM " + SIGNAL_TABLE + " WHERE created_at >= NOW() - INTERVAL 24 HOUR "
            + "GROUP BY trendSignal, reasonBucket ORDER BY signals DESC LIMIT 20")
    List<Map<String, Object>> signalRollup();

    @Select("SELECT id, contract_code AS contractCode, period, bar_time AS barTime, direction, regime, "
            + "signal_score AS signalScore, long_score AS longScore, short_score AS shortScore, score_gap AS scoreGap, "
            + "close_price AS closePrice, atr_pct AS atrPct, adx, momentum_pct AS momentumPct, volume_ratio AS volumeRatio, "
            + "entry_reason AS entryReason, invalidation_reason AS invalidationReason, model_version AS modelVersion, "
            + "created_at AS createdAt FROM " + CANDIDATE_SIGNAL_TABLE + " ORDER BY created_at DESC, id DESC LIMIT 80")
    List<Map<String, Object>> recentCandidateSignals();
}
