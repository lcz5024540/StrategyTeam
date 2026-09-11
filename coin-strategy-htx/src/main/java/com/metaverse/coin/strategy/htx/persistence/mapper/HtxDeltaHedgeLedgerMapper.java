package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface HtxDeltaHedgeLedgerMapper extends BaseMapper<HtxDeltaHedgeLedgerEntity> {

    String COLUMNS = "id, hedge_key, symbol, contract_code, epoch, action, target_short_base, "
            + "contract_volume, side, position_side, reduce_only, client_order_id, "
            + "exchange_order_id, exchange_client_order_id, exchange_state, filled_contracts, "
            + "trade_avg_price, fee, fee_currency, realized_profit, planner_state, status, reason, "
            + "created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_delta_hedge_ledger WHERE hedge_key = #{hedgeKey} LIMIT 1")
    HtxDeltaHedgeLedgerEntity findByHedgeKey(@Param("hedgeKey") String hedgeKey);

    @Select("SELECT " + COLUMNS + " FROM htx_delta_hedge_ledger "
            + "WHERE status = #{status} ORDER BY id LIMIT #{limit}")
    List<HtxDeltaHedgeLedgerEntity> findByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM htx_delta_hedge_ledger WHERE status = #{status}")
    int countByStatus(@Param("status") String status);

    @Update("UPDATE htx_delta_hedge_ledger SET status = #{nextStatus}, reason = #{nextReason}, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE status = #{expectedStatus} AND exchange_order_id IS NULL "
            + "AND filled_contracts = 0 AND reason LIKE #{reasonPattern}")
    int reclassifyPreAcceptRejects(@Param("expectedStatus") String expectedStatus,
                                   @Param("nextStatus") String nextStatus,
                                   @Param("nextReason") String nextReason,
                                   @Param("reasonPattern") String reasonPattern);

    @Select("SELECT " + COLUMNS + " FROM htx_delta_hedge_ledger WHERE id = #{id} LIMIT 1")
    HtxDeltaHedgeLedgerEntity findById(@Param("id") long id);

    @Update("UPDATE htx_delta_hedge_ledger SET status = #{nextStatus}, reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIfCurrent(@Param("id") long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("nextStatus") String nextStatus,
                              @Param("reason") String reason);

    @Update("UPDATE htx_delta_hedge_ledger SET status = #{nextStatus}, "
            + "exchange_order_id = #{exchangeOrderId}, exchange_client_order_id = #{exchangeClientOrderId}, "
            + "reason = #{reason}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateAcceptedIfCurrent(@Param("id") long id,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("nextStatus") String nextStatus,
                                @Param("exchangeOrderId") Long exchangeOrderId,
                                @Param("exchangeClientOrderId") String exchangeClientOrderId,
                                @Param("reason") String reason);

    @Update("UPDATE htx_delta_hedge_ledger SET status = #{nextStatus}, "
            + "exchange_order_id = #{exchangeOrderId}, exchange_client_order_id = #{exchangeClientOrderId}, "
            + "exchange_state = #{exchangeState}, filled_contracts = #{filledContracts}, "
            + "trade_avg_price = #{tradeAvgPrice}, fee = #{fee}, fee_currency = #{feeCurrency}, "
            + "realized_profit = #{realizedProfit}, reason = #{reason}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateReconciliationIfCurrent(@Param("id") long id,
                                      @Param("expectedStatus") String expectedStatus,
                                      @Param("nextStatus") String nextStatus,
                                      @Param("exchangeOrderId") Long exchangeOrderId,
                                      @Param("exchangeClientOrderId") String exchangeClientOrderId,
                                      @Param("exchangeState") String exchangeState,
                                      @Param("filledContracts") BigDecimal filledContracts,
                                      @Param("tradeAvgPrice") BigDecimal tradeAvgPrice,
                                      @Param("fee") BigDecimal fee,
                                      @Param("feeCurrency") String feeCurrency,
                                      @Param("realizedProfit") BigDecimal realizedProfit,
                                      @Param("reason") String reason);
}
