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
    String ACCOUNT_TABLE = TREND_SCHEMA + "htx_trade_account";
    String LEDGER_TABLE = TREND_SCHEMA + "coin_strategy_ledger_event";

    String LEDGER_TURNOVER = "CASE WHEN event_type IN ('OPEN_FILL','CLOSE_FILL') THEN COALESCE(amount_usdt, 0) ELSE 0 END";
    String LEDGER_FEE = "CASE WHEN event_type = 'FEE' THEN COALESCE(fee_usdt, 0) ELSE 0 END";
    String LEDGER_NET = "COALESCE(net_pnl_after_fee, 0)";

    String CLOSED_NET_PROFIT = "COALESCE(realized_profit, 0) - COALESCE(entry_fee, 0) - COALESCE(close_fee, 0)";
    String CLOSED_TURNOVER = "COALESCE(entry_filled_contracts, 0) * COALESCE(contract_size, 0) * COALESCE(entry_avg_price, 0) "
            + "+ COALESCE(close_filled_contracts, 0) * COALESCE(contract_size, 0) * COALESCE(close_avg_price, 0)";
    String DIMENSION_PNL_METRICS = "COUNT(*) AS closedPositions, "
            + "SUM(CASE WHEN " + CLOSED_NET_PROFIT + " > 0 THEN 1 ELSE 0 END) AS winningPositions, "
            + "SUM(CASE WHEN " + CLOSED_NET_PROFIT + " < 0 THEN 1 ELSE 0 END) AS losingPositions, "
            + "COALESCE(SUM(realized_profit), 0) AS realizedProfit, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS fees, "
            + "COALESCE(SUM(CASE WHEN " + CLOSED_NET_PROFIT + " > 0 THEN " + CLOSED_NET_PROFIT + " ELSE 0 END), 0) AS grossProfit, "
            + "COALESCE(SUM(CASE WHEN " + CLOSED_NET_PROFIT + " < 0 THEN -(" + CLOSED_NET_PROFIT + ") ELSE 0 END), 0) AS grossLoss, "
            + "COALESCE(SUM(" + CLOSED_NET_PROFIT + "), 0) AS netProfit, "
            + "COALESCE(SUM(" + CLOSED_TURNOVER + "), 0) AS turnover ";
    String ENTRY_MODEL_BUCKET = "CASE "
            + "WHEN entry_signal LIKE '%funding carry%' THEN 'funding carry' "
            + "WHEN entry_signal LIKE '%ensemble entry%' THEN 'ensemble' "
            + "WHEN entry_signal LIKE '%Fibonacci%' THEN 'fibonacci continuation' "
            + "WHEN entry_signal LIKE '%Donchian%' OR entry_signal LIKE '%breakout%' OR entry_signal LIKE '%broke%' THEN 'breakout' "
            + "WHEN entry_signal IS NULL OR entry_signal = '' THEN 'unknown' "
            + "ELSE 'other' END";
    String MARKET_REGIME_BUCKET = "CASE "
            + "WHEN entry_signal LIKE '%market anchor counter%' THEN 'counter market' "
            + "WHEN entry_signal LIKE '%market anchor aligned BROAD_UP%' OR entry_signal LIKE '%breadth=BROAD_UP%' OR entry_signal LIKE '%BROAD_UP%' THEN 'broad up' "
            + "WHEN entry_signal LIKE '%market anchor aligned BROAD_DOWN%' OR entry_signal LIKE '%breadth=BROAD_DOWN%' OR entry_signal LIKE '%BROAD_DOWN%' THEN 'broad down' "
            + "WHEN entry_signal LIKE '%MIXED%' THEN 'mixed' "
            + "WHEN entry_signal IS NULL OR entry_signal = '' THEN 'unknown' "
            + "ELSE 'unclassified' END";

    @Select("SELECT COUNT(*) AS totalPositions, "
            + "SUM(CASE WHEN status IN ('INTENT','ENTRY_SENT','ACTIVE','CLOSE_SENT','UNKNOWN') THEN 1 ELSE 0 END) AS activePositions, "
            + "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS closedPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND realized_profit - entry_fee - close_fee > 0 THEN 1 ELSE 0 END) AS winningPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() THEN 1 ELSE 0 END) AS todayClosedPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() AND realized_profit - entry_fee - close_fee > 0 THEN 1 ELSE 0 END) AS todayWinningPositions, "
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

    @Select("SELECT COUNT(*) AS ledgerEventCount, "
            + "COALESCE(SUM(" + LEDGER_TURNOVER + "), 0) AS ledgerTotalTurnover, "
            + "COALESCE(SUM(CASE WHEN occurred_at >= CURDATE() THEN " + LEDGER_TURNOVER + " ELSE 0 END), 0) AS ledgerTodayTurnover, "
            + "COALESCE(SUM(" + LEDGER_FEE + "), 0) AS ledgerTotalFees, "
            + "COALESCE(SUM(CASE WHEN occurred_at >= CURDATE() THEN " + LEDGER_FEE + " ELSE 0 END), 0) AS ledgerTodayFees, "
            + "COALESCE(SUM(" + LEDGER_NET + "), 0) AS ledgerNetRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN occurred_at >= CURDATE() THEN " + LEDGER_NET + " ELSE 0 END), 0) AS ledgerTodayNetRealizedProfit, "
            + "MAX(occurred_at) AS latestLedgerAt FROM " + LEDGER_TABLE
            + " WHERE strategy_family = 'TREND' AND strategy_name = 'HTX_TREND_V1'")
    Map<String, Object> ledgerSummary();

    @Select("SELECT account_label AS accountLabel, COUNT(*) AS ledgerEventCount, "
            + "COALESCE(SUM(" + LEDGER_TURNOVER + "), 0) AS ledgerTotalTurnover, "
            + "COALESCE(SUM(CASE WHEN occurred_at >= CURDATE() THEN " + LEDGER_TURNOVER + " ELSE 0 END), 0) AS ledgerTodayTurnover, "
            + "COALESCE(SUM(" + LEDGER_FEE + "), 0) AS ledgerTotalFees, "
            + "COALESCE(SUM(CASE WHEN occurred_at >= CURDATE() THEN " + LEDGER_FEE + " ELSE 0 END), 0) AS ledgerTodayFees, "
            + "COALESCE(SUM(" + LEDGER_NET + "), 0) AS ledgerNetRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN occurred_at >= CURDATE() THEN " + LEDGER_NET + " ELSE 0 END), 0) AS ledgerTodayNetRealizedProfit, "
            + "MAX(occurred_at) AS latestLedgerAt FROM " + LEDGER_TABLE
            + " WHERE strategy_family = 'TREND' AND strategy_name = 'HTX_TREND_V1' GROUP BY account_label")
    List<Map<String, Object>> ledgerAccountSummaries();




    @Select("SELECT COUNT(*) AS closedPositions, "
            + "COALESCE(SUM(CASE WHEN closed.net_profit > 0 THEN 1 ELSE 0 END), 0) AS winningPositions, "
            + "COALESCE(SUM(CASE WHEN closed.net_profit < 0 THEN 1 ELSE 0 END), 0) AS losingPositions, "
            + "COALESCE(SUM(CASE WHEN closed.net_profit > 0 THEN closed.net_profit ELSE 0 END), 0) AS grossProfit, "
            + "COALESCE(SUM(CASE WHEN closed.net_profit < 0 THEN -closed.net_profit ELSE 0 END), 0) AS grossLoss, "
            + "COALESCE(SUM(closed.net_profit), 0) AS netRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN closed.updated_at >= CURDATE() THEN 1 ELSE 0 END), 0) AS todayClosedPositions, "
            + "COALESCE(SUM(CASE WHEN closed.updated_at >= CURDATE() AND closed.net_profit > 0 THEN 1 ELSE 0 END), 0) AS todayWinningPositions, "
            + "COALESCE(SUM(CASE WHEN closed.updated_at >= CURDATE() AND closed.net_profit < 0 THEN 1 ELSE 0 END), 0) AS todayLosingPositions, "
            + "COALESCE(SUM(CASE WHEN closed.updated_at >= CURDATE() AND closed.net_profit > 0 THEN closed.net_profit ELSE 0 END), 0) AS todayGrossProfit, "
            + "COALESCE(SUM(CASE WHEN closed.updated_at >= CURDATE() AND closed.net_profit < 0 THEN -closed.net_profit ELSE 0 END), 0) AS todayGrossLoss, "
            + "COALESCE(SUM(CASE WHEN closed.updated_at >= CURDATE() THEN closed.net_profit ELSE 0 END), 0) AS todayNetRealizedProfit "
            + "FROM (SELECT updated_at, COALESCE(realized_profit, 0) - COALESCE(entry_fee, 0) - COALESCE(close_fee, 0) AS net_profit "
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE') closed")
    Map<String, Object> performanceSummary();

    @Select("SELECT accountLabels.accountLabel AS accountLabel, COALESCE(accounts.account_role, 'LEDGER') AS accountRole, "
            + "COALESCE(accounts.enabled, 1) AS accountEnabled, COALESCE(accounts.trend_enabled, 1) AS trendEnabled, "
            + "COALESCE(accounts.mirror_enabled, 0) AS mirrorEnabled, "
            + "COALESCE(pos.totalPositions, 0) AS totalPositions, "
            + "COALESCE(pos.activePositions, 0) AS activePositions, "
            + "COALESCE(pos.closedPositions, 0) AS closedPositions, "
            + "COALESCE(pos.winningPositions, 0) AS winningPositions, "
            + "COALESCE(pos.todayClosedPositions, 0) AS todayClosedPositions, "
            + "COALESCE(pos.todayWinningPositions, 0) AS todayWinningPositions, "
            + "COALESCE(pos.totalTurnover, 0) AS totalTurnover, "
            + "COALESCE(pos.todayTurnover, 0) AS todayTurnover, "
            + "COALESCE(pos.totalFees, 0) AS totalFees, "
            + "COALESCE(pos.todayFees, 0) AS todayFees, "
            + "COALESCE(pos.realizedProfit, 0) AS realizedProfit, "
            + "COALESCE(pos.todayRealizedProfit, 0) AS todayRealizedProfit, "
            + "COALESCE(pos.netRealizedProfit, 0) AS netRealizedProfit, "
            + "COALESCE(pos.todayNetRealizedProfit, 0) AS todayNetRealizedProfit, "
            + "COALESCE(ord.totalOrders, 0) AS totalOrders, "
            + "COALESCE(ord.todayOrders, 0) AS todayOrders, "
            + "COALESCE(ord.openOrders, 0) AS openOrders, "
            + "COALESCE(ord.entryOrders, 0) AS entryOrders, "
            + "COALESCE(ord.closeOrders, 0) AS closeOrders, "
            + "COALESCE(ord.orderNotional, 0) AS orderNotional, "
            + "COALESCE(ord.todayOrderNotional, 0) AS todayOrderNotional, "
            + "COALESCE(ord.orderFees, 0) AS orderFees, "
            + "COALESCE(ord.todayOrderFees, 0) AS todayOrderFees, "
            + "COALESCE(ord.estimatedOrderFees, 0) AS estimatedOrderFees, "
            + "COALESCE(ord.todayEstimatedOrderFees, 0) AS todayEstimatedOrderFees, "
            + "COALESCE(ord.orderRealizedProfit, 0) AS orderRealizedProfit, "
            + "COALESCE(ord.todayOrderRealizedProfit, 0) AS todayOrderRealizedProfit, "
            + "ord.latestOrderAt AS latestOrderAt, pos.latestPositionAt AS latestPositionAt "
            + "FROM ("
            + "SELECT account_label AS accountLabel FROM " + ACCOUNT_TABLE + " WHERE trend_enabled = 1 "
            + "UNION SELECT account_label AS accountLabel FROM " + POSITION_TABLE + " GROUP BY account_label "
            + "UNION SELECT account_label AS accountLabel FROM " + ORDER_TABLE + " GROUP BY account_label"
            + ") accountLabels "
            + "LEFT JOIN " + ACCOUNT_TABLE + " accounts ON accounts.account_label = accountLabels.accountLabel "
            + "LEFT JOIN (SELECT account_label AS accountLabel, COUNT(*) AS totalPositions, "
            + "SUM(CASE WHEN status IN ('INTENT','ENTRY_SENT','ACTIVE','CLOSE_SENT','UNKNOWN') THEN 1 ELSE 0 END) AS activePositions, "
            + "SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS closedPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND realized_profit - entry_fee - close_fee > 0 THEN 1 ELSE 0 END) AS winningPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() THEN 1 ELSE 0 END) AS todayClosedPositions, "
            + "SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() AND realized_profit - entry_fee - close_fee > 0 THEN 1 ELSE 0 END) AS todayWinningPositions, "
            + "COALESCE(SUM(" + CLOSED_TURNOVER + "), 0) AS totalTurnover, "
            + "COALESCE(SUM(CASE WHEN updated_at >= CURDATE() THEN " + CLOSED_TURNOVER + " ELSE 0 END), 0) AS todayTurnover, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS totalFees, "
            + "COALESCE(SUM(CASE WHEN updated_at >= CURDATE() THEN entry_fee + close_fee ELSE 0 END), 0) AS todayFees, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' THEN realized_profit ELSE 0 END), 0) AS realizedProfit, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() THEN realized_profit ELSE 0 END), 0) AS todayRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' THEN realized_profit - entry_fee - close_fee ELSE 0 END), 0) AS netRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' AND updated_at >= CURDATE() THEN realized_profit - entry_fee - close_fee ELSE 0 END), 0) AS todayNetRealizedProfit, "
            + "MAX(updated_at) AS latestPositionAt FROM " + POSITION_TABLE + " GROUP BY account_label) pos "
            + "ON pos.accountLabel = accountLabels.accountLabel "
            + "LEFT JOIN (SELECT o.account_label AS accountLabel, COUNT(*) AS totalOrders, "
            + "SUM(CASE WHEN o.created_at >= CURDATE() THEN 1 ELSE 0 END) AS todayOrders, "
            + "SUM(CASE WHEN o.status IN ('INTENT','ACCEPTED') THEN 1 ELSE 0 END) AS openOrders, "
            + "SUM(CASE WHEN o.leg = 'ENTRY' THEN 1 ELSE 0 END) AS entryOrders, "
            + "SUM(CASE WHEN o.leg = 'CLOSE' THEN 1 ELSE 0 END) AS closeOrders, "
            + "COALESCE(SUM(COALESCE(o.filled_contracts, 0) * COALESCE(p.contract_size, 0) * COALESCE(o.average_price, 0)), 0) AS orderNotional, "
            + "COALESCE(SUM(CASE WHEN o.created_at >= CURDATE() THEN COALESCE(o.filled_contracts, 0) * COALESCE(p.contract_size, 0) * COALESCE(o.average_price, 0) ELSE 0 END), 0) AS todayOrderNotional, "
            + "COALESCE(SUM(o.fee), 0) AS orderFees, "
            + "COALESCE(SUM(CASE WHEN o.created_at >= CURDATE() THEN o.fee ELSE 0 END), 0) AS todayOrderFees, "
            + "COALESCE(SUM(o.estimated_fee), 0) AS estimatedOrderFees, "
            + "COALESCE(SUM(CASE WHEN o.created_at >= CURDATE() THEN o.estimated_fee ELSE 0 END), 0) AS todayEstimatedOrderFees, "
            + "COALESCE(SUM(o.realized_profit), 0) AS orderRealizedProfit, "
            + "COALESCE(SUM(CASE WHEN o.created_at >= CURDATE() THEN o.realized_profit ELSE 0 END), 0) AS todayOrderRealizedProfit, "
            + "MAX(o.updated_at) AS latestOrderAt FROM " + ORDER_TABLE + " o LEFT JOIN " + POSITION_TABLE + " p ON p.id = o.position_id "
            + "GROUP BY o.account_label) ord ON ord.accountLabel = accountLabels.accountLabel "
            + "ORDER BY COALESCE(pos.activePositions, 0) DESC, COALESCE(pos.todayNetRealizedProfit, ord.todayOrderRealizedProfit, 0) DESC, "
            + "COALESCE(accounts.sort_order, 999), accountLabels.accountLabel")
    List<Map<String, Object>> accountSummaries();

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
            + "entry_signal AS entrySignal, exit_reason AS exitReason, message, account_label AS accountLabel, "
            + "entry_fee_rate AS entryFeeRate, estimated_entry_fee AS estimatedEntryFee, "
            + "estimated_close_fee AS estimatedCloseFee, expected_net_after_fees AS expectedNetAfterFees, "
            + "sizing_multiplier AS sizingMultiplier, sizing_reason AS sizingReason, entry_gate_reason AS entryGateReason, "
            + "created_at AS createdAt, updated_at AS updatedAt "
            + "FROM " + POSITION_TABLE + " WHERE status IN ('INTENT','ENTRY_SENT','ACTIVE','CLOSE_SENT','UNKNOWN') "
            + "ORDER BY updated_at DESC, id DESC LIMIT 50")
    List<Map<String, Object>> activePositions();

    @Select("SELECT id, contract_code AS contractCode, status, side, contract_volume AS contractVolume, "
            + "entry_filled_contracts AS entryFilledContracts, close_filled_contracts AS closeFilledContracts, "
            + "entry_avg_price AS entryAvgPrice, close_avg_price AS closeAvgPrice, entry_fee AS entryFee, close_fee AS closeFee, "
            + "realized_profit AS realizedProfit, realized_profit - entry_fee - close_fee AS netRealizedProfit, "
            + "(" + CLOSED_TURNOVER + ") AS turnover, stop_price AS stopPrice, trailing_stop_price AS trailingStopPrice, "
            + "entry_signal AS entrySignal, exit_reason AS exitReason, message, account_label AS accountLabel, "
            + "entry_fee_rate AS entryFeeRate, estimated_entry_fee AS estimatedEntryFee, "
            + "estimated_close_fee AS estimatedCloseFee, expected_net_after_fees AS expectedNetAfterFees, "
            + "sizing_multiplier AS sizingMultiplier, sizing_reason AS sizingReason, entry_gate_reason AS entryGateReason, "
            + "created_at AS createdAt, updated_at AS updatedAt "
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

    @Select("SELECT side AS bucket, side, " + DIMENSION_PNL_METRICS
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' GROUP BY side ORDER BY netProfit DESC")
    List<Map<String, Object>> sidePnl();

    @Select("SELECT side AS bucket, side, " + DIMENSION_PNL_METRICS
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' AND updated_at >= CURDATE() "
            + "GROUP BY side ORDER BY netProfit DESC")
    List<Map<String, Object>> todaySidePnl();

    @Select("SELECT " + ENTRY_MODEL_BUCKET + " AS bucket, side, " + DIMENSION_PNL_METRICS
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' "
            + "GROUP BY bucket, side ORDER BY netProfit DESC LIMIT 30")
    List<Map<String, Object>> entryModelPnl();

    @Select("SELECT " + MARKET_REGIME_BUCKET + " AS bucket, side, " + DIMENSION_PNL_METRICS
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' "
            + "GROUP BY bucket, side ORDER BY netProfit DESC LIMIT 30")
    List<Map<String, Object>> marketRegimePnl();

    @Select("SELECT contract_code AS bucket, side, " + DIMENSION_PNL_METRICS
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' "
            + "GROUP BY contract_code, side ORDER BY netProfit ASC LIMIT 30")
    List<Map<String, Object>> symbolPnl();

    @Select("SELECT CASE "
            + "WHEN exit_reason LIKE '%loss control%' THEN 'flat loss control' "
            + "WHEN exit_reason LIKE '%fee protection%' THEN 'flat after fee protection' "
            + "WHEN exit_reason LIKE '%trailing stop%' THEN 'trailing stop' "
            + "WHEN exit_reason LIKE '%take profit%' THEN 'take profit' "
            + "WHEN exit_reason IS NULL OR exit_reason = '' THEN 'unknown' "
            + "ELSE LEFT(exit_reason, 96) END AS bucket, side, " + DIMENSION_PNL_METRICS
            + "FROM " + POSITION_TABLE + " WHERE status = 'DONE' AND updated_at >= CURDATE() "
            + "GROUP BY bucket, side ORDER BY netProfit ASC LIMIT 20")
    List<Map<String, Object>> exitReasonPnl();

    @Select("SELECT o.id, o.position_id AS positionId, o.contract_code AS contractCode, o.leg, o.trend_side AS trendSide, "
            + "o.order_side AS orderSide, o.position_side AS positionSide, o.reduce_only AS reduceOnly, "
            + "o.client_order_id AS clientOrderId, o.exchange_order_id AS exchangeOrderId, o.request_volume AS requestVolume, "
            + "o.filled_contracts AS filledContracts, o.average_price AS averagePrice, o.fee, o.realized_profit AS realizedProfit, "
            + "o.filled_contracts * p.contract_size * o.average_price AS notional, o.exchange_state AS exchangeState, "
            + "o.account_label AS accountLabel, o.fee_rate_used AS feeRateUsed, o.estimated_fee AS estimatedFee, "
            + "o.estimated_open_fee AS estimatedOpenFee, o.estimated_close_fee AS estimatedCloseFee, "
            + "o.sizing_multiplier AS sizingMultiplier, o.sizing_reason AS sizingReason, o.entry_gate_reason AS entryGateReason, "
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

    @Select("SELECT id, event_key AS eventKey, event_type AS eventType, account_label AS accountLabel, "
            + "contract_code AS contractCode, position_id AS positionId, order_id AS orderId, "
            + "client_order_id AS clientOrderId, side, execution_price_usdt AS executionPriceUsdt, "
            + "entry_price_usdt AS entryPriceUsdt, close_price_usdt AS closePriceUsdt, "
            + "filled_contracts AS filledContracts, contract_size AS contractSize, amount_usdt AS amountUsdt, "
            + "fee_usdt AS feeUsdt, gross_pnl_usdt AS grossPnlUsdt, net_pnl_after_fee AS netPnlAfterFee, "
            + "reason, occurred_at AS occurredAt FROM " + LEDGER_TABLE
            + " WHERE strategy_family = 'TREND' AND strategy_name = 'HTX_TREND_V1' "
            + "ORDER BY occurred_at DESC, id DESC LIMIT 120")
    List<Map<String, Object>> recentLedgerEvents();

    @Select("SELECT id, contract_code AS contractCode, period, bar_time AS barTime, direction, regime, "
            + "signal_score AS signalScore, long_score AS longScore, short_score AS shortScore, score_gap AS scoreGap, "
            + "close_price AS closePrice, atr_pct AS atrPct, adx, momentum_pct AS momentumPct, volume_ratio AS volumeRatio, "
            + "entry_reason AS entryReason, invalidation_reason AS invalidationReason, model_version AS modelVersion, "
            + "created_at AS createdAt FROM " + CANDIDATE_SIGNAL_TABLE + " ORDER BY created_at DESC, id DESC LIMIT 80")
    List<Map<String, Object>> recentCandidateSignals();
}
