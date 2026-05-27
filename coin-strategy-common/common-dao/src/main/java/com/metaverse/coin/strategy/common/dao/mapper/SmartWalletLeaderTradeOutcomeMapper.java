package com.metaverse.coin.strategy.common.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmartWalletLeaderTradeOutcomeMapper extends BaseMapper<SmartWalletLeaderTradeOutcomeEntity> {

    /**
     * 单语句 upsert：依赖唯一索引 uk_sw_leader_outcome_wallet_market_outcome
     * (wallet_address, market_id, outcome)。
     *
     * <p>相比原先的 selectOne→insert/update 双段逻辑，这里仅产生一条 SQL，
     * 在 MySQL 上由 InnoDB 一次性获取/释放行锁与间隙锁，把单行的持锁窗口压到毫秒级，
     * 避免被外层长事务放大成 Lock wait timeout（参见 P1-8 复盘）。
     *
     * <p>ON DUPLICATE KEY UPDATE 中显式跳过：
     * <ul>
     *   <li>id —— 自增主键，不能改</li>
     *   <li>wallet_address / market_id / outcome —— 唯一键自身，保持稳定</li>
     *   <li>created_at —— 首次入库时间，刷新时保留</li>
     * </ul>
     */
    @Insert("INSERT INTO smart_wallet_leader_trade_outcome (" +
            "  wallet_address, market_id, market_info_id, question, outcome, token_id," +
            "  first_trade_at, last_trade_at, buy_count, sell_count," +
            "  buy_shares, sell_shares, net_shares, avg_buy_price," +
            "  buy_cost_usdc, sell_proceeds_usdc," +
            "  resolved_outcome, settlement_price, estimated_settlement_value_usdc," +
            "  estimated_realized_pnl_usdc, estimated_roi_rate," +
            "  is_win, outcome_status, source_confidence, data_source," +
            "  created_at, updated_at" +
            ") VALUES (" +
            "  #{walletAddress}, #{marketId}, #{marketInfoId}, #{question}, #{outcome}, #{tokenId}," +
            "  #{firstTradeAt}, #{lastTradeAt}, #{buyCount}, #{sellCount}," +
            "  #{buyShares}, #{sellShares}, #{netShares}, #{avgBuyPrice}," +
            "  #{buyCostUsdc}, #{sellProceedsUsdc}," +
            "  #{resolvedOutcome}, #{settlementPrice}, #{estimatedSettlementValueUsdc}," +
            "  #{estimatedRealizedPnlUsdc}, #{estimatedRoiRate}," +
            "  #{isWin}, #{outcomeStatus}, #{sourceConfidence}, #{dataSource}," +
            "  #{createdAt}, #{updatedAt}" +
            ") ON DUPLICATE KEY UPDATE" +
            "  market_info_id = VALUES(market_info_id)," +
            "  question = VALUES(question)," +
            "  token_id = VALUES(token_id)," +
            "  first_trade_at = VALUES(first_trade_at)," +
            "  last_trade_at = VALUES(last_trade_at)," +
            "  buy_count = VALUES(buy_count)," +
            "  sell_count = VALUES(sell_count)," +
            "  buy_shares = VALUES(buy_shares)," +
            "  sell_shares = VALUES(sell_shares)," +
            "  net_shares = VALUES(net_shares)," +
            "  avg_buy_price = VALUES(avg_buy_price)," +
            "  buy_cost_usdc = VALUES(buy_cost_usdc)," +
            "  sell_proceeds_usdc = VALUES(sell_proceeds_usdc)," +
            "  resolved_outcome = VALUES(resolved_outcome)," +
            "  settlement_price = VALUES(settlement_price)," +
            "  estimated_settlement_value_usdc = VALUES(estimated_settlement_value_usdc)," +
            "  estimated_realized_pnl_usdc = VALUES(estimated_realized_pnl_usdc)," +
            "  estimated_roi_rate = VALUES(estimated_roi_rate)," +
            "  is_win = VALUES(is_win)," +
            "  outcome_status = VALUES(outcome_status)," +
            "  source_confidence = VALUES(source_confidence)," +
            "  data_source = VALUES(data_source)," +
            "  updated_at = VALUES(updated_at)")
    int upsert(SmartWalletLeaderTradeOutcomeEntity entity);
}
