package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface HtxInventoryRecycleSagaMapper extends BaseMapper<HtxInventoryRecycleSagaEntity> {

    String COLUMNS = "id, saga_key, symbol, contract_code, epoch, spot_fill_ledger_id, spot_sold_base, "
            + "available_short_base, close_contracts, close_client_order_id, planner_state, status, reason, "
            + "close_exchange_order_id, close_exchange_client_order_id, close_exchange_state, "
            + "close_filled_contracts, close_trade_avg_price, close_fee, close_fee_currency, "
            + "close_realized_profit, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_inventory_recycle_saga WHERE saga_key = #{sagaKey} LIMIT 1")
    HtxInventoryRecycleSagaEntity findBySagaKey(@Param("sagaKey") String sagaKey);

    @Select("SELECT " + COLUMNS + " FROM htx_inventory_recycle_saga "
            + "WHERE status = #{status} ORDER BY id LIMIT #{limit}")
    List<HtxInventoryRecycleSagaEntity> findByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM htx_inventory_recycle_saga WHERE status = #{status}")
    int countByStatus(@Param("status") String status);

    @Select("SELECT " + COLUMNS + " FROM htx_inventory_recycle_saga WHERE id = #{id} LIMIT 1")
    HtxInventoryRecycleSagaEntity findById(@Param("id") long id);

    @Update("UPDATE htx_inventory_recycle_saga SET status = #{nextStatus}, reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIfCurrent(@Param("id") long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("nextStatus") String nextStatus,
                              @Param("reason") String reason);

    @Update("UPDATE htx_inventory_recycle_saga SET status = #{nextStatus}, "
            + "close_exchange_order_id = #{exchangeOrderId}, "
            + "close_exchange_client_order_id = #{exchangeClientOrderId}, "
            + "reason = #{reason}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateAcceptedIfCurrent(@Param("id") long id,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("nextStatus") String nextStatus,
                                @Param("exchangeOrderId") Long exchangeOrderId,
                                @Param("exchangeClientOrderId") String exchangeClientOrderId,
                                @Param("reason") String reason);

    @Update("UPDATE htx_inventory_recycle_saga SET status = #{nextStatus}, "
            + "close_exchange_order_id = #{exchangeOrderId}, "
            + "close_exchange_client_order_id = #{exchangeClientOrderId}, "
            + "close_exchange_state = #{exchangeState}, close_filled_contracts = #{filledContracts}, "
            + "close_trade_avg_price = #{tradeAvgPrice}, close_fee = #{fee}, "
            + "close_fee_currency = #{feeCurrency}, close_realized_profit = #{realizedProfit}, "
            + "reason = #{reason}, updated_at = CURRENT_TIMESTAMP(3) "
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
