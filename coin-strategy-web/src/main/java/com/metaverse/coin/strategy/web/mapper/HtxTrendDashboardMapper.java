package com.metaverse.coin.strategy.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** Read-only SQL projection of the HTX trend execution ledger. */
@Mapper
public interface HtxTrendDashboardMapper {

    @Select("SELECT COUNT(*) AS totalPositions, "
            + "SUM(status IN ('INTENT','ENTRY_SENT','ACTIVE','CLOSE_SENT','UNKNOWN')) AS activePositions, "
            + "SUM(status = 'DONE') AS closedPositions, "
            + "SUM(status = 'DONE' AND realized_profit > 0) AS winningPositions, "
            + "COALESCE(SUM(entry_filled_contracts * contract_size * entry_avg_price "
            + "+ close_filled_contracts * contract_size * close_avg_price), 0) AS totalTurnover, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS totalFees, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' THEN realized_profit ELSE 0 END), 0) AS realizedProfit, "
            + "COALESCE(SUM(CASE WHEN status = 'DONE' THEN realized_profit - entry_fee - close_fee ELSE 0 END), 0) AS netRealizedProfit "
            + "FROM htx_trend_position")
    Map<String, Object> summary();

    @Select("SELECT id, contract_code AS contractCode, status, side, contract_volume AS contractVolume, "
            + "entry_filled_contracts AS entryFilledContracts, entry_avg_price AS entryAvgPrice, entry_fee AS entryFee, "
            + "stop_price AS stopPrice, trailing_stop_price AS trailingStopPrice, take_profit_price AS takeProfitPrice, "
            + "entry_signal AS entrySignal, created_at AS createdAt, updated_at AS updatedAt "
            + "FROM htx_trend_position WHERE status IN ('INTENT','ENTRY_SENT','ACTIVE','CLOSE_SENT','UNKNOWN') "
            + "ORDER BY updated_at DESC, id DESC LIMIT 50")
    List<Map<String, Object>> activePositions();

    @Select("SELECT id, contract_code AS contractCode, status, side, contract_volume AS contractVolume, "
            + "entry_avg_price AS entryAvgPrice, close_avg_price AS closeAvgPrice, entry_fee AS entryFee, close_fee AS closeFee, "
            + "realized_profit AS realizedProfit, entry_signal AS entrySignal, exit_reason AS exitReason, "
            + "created_at AS createdAt, updated_at AS updatedAt "
            + "FROM htx_trend_position ORDER BY updated_at DESC, id DESC LIMIT 100")
    List<Map<String, Object>> recentPositions();

    @Select("SELECT DATE(updated_at) AS tradeDate, COUNT(*) AS closedPositions, "
            + "COALESCE(SUM(realized_profit), 0) AS realizedProfit, "
            + "COALESCE(SUM(entry_fee + close_fee), 0) AS fees, "
            + "COALESCE(SUM(realized_profit - entry_fee - close_fee), 0) AS netProfit "
            + "FROM htx_trend_position WHERE status = 'DONE' GROUP BY DATE(updated_at) "
            + "ORDER BY tradeDate DESC LIMIT 30")
    List<Map<String, Object>> dailyPnl();
}
