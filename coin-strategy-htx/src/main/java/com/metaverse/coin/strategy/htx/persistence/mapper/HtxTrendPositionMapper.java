package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPositionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Mapper
public interface HtxTrendPositionMapper extends BaseMapper<HtxTrendPositionEntity> {

    String COLUMNS = "id, position_key, contract_code, status, side, contract_size, contract_volume, leverage, "
            + "entry_client_order_id, entry_order_id, entry_filled_contracts, entry_avg_price, entry_fee, "
            + "close_client_order_id, close_order_id, close_filled_contracts, close_avg_price, close_fee, "
            + "realized_profit, stop_price, trailing_stop_price, take_profit_price, highest_price, lowest_price, "
            + "entry_signal, exit_reason, message, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_trend_position WHERE contract_code = #{contractCode} "
            + "AND status IN ('INTENT', 'ENTRY_SENT', 'ACTIVE', 'CLOSE_SENT', 'UNKNOWN') "
            + "ORDER BY id LIMIT 1")
    HtxTrendPositionEntity findActive(@Param("contractCode") String contractCode);

    @Select("SELECT " + COLUMNS + " FROM htx_trend_position WHERE contract_code = #{contractCode} "
            + "AND status = 'DONE' ORDER BY updated_at DESC LIMIT 1")
    HtxTrendPositionEntity findLastDone(@Param("contractCode") String contractCode);

    @Update("UPDATE htx_trend_position SET status = #{nextStatus}, entry_order_id = #{orderId}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int markEntryAccepted(@Param("id") long id,
                          @Param("expectedStatus") String expectedStatus,
                          @Param("nextStatus") String nextStatus,
                          @Param("orderId") Long orderId,
                          @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = 'ACTIVE', entry_filled_contracts = #{filledContracts}, "
            + "entry_avg_price = #{avgPrice}, entry_fee = #{fee}, highest_price = #{highestPrice}, "
            + "lowest_price = #{lowestPrice}, message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id}")
    int markEntryFilled(@Param("id") long id,
                        @Param("filledContracts") BigDecimal filledContracts,
                        @Param("avgPrice") BigDecimal avgPrice,
                        @Param("fee") BigDecimal fee,
                        @Param("highestPrice") BigDecimal highestPrice,
                        @Param("lowestPrice") BigDecimal lowestPrice,
                        @Param("message") String message);

    @Update("UPDATE htx_trend_position SET stop_price = #{stopPrice}, trailing_stop_price = #{trailingStopPrice}, "
            + "take_profit_price = #{takeProfitPrice}, highest_price = #{highestPrice}, lowest_price = #{lowestPrice}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateRiskLevels(@Param("id") long id,
                         @Param("stopPrice") BigDecimal stopPrice,
                         @Param("trailingStopPrice") BigDecimal trailingStopPrice,
                         @Param("takeProfitPrice") BigDecimal takeProfitPrice,
                         @Param("highestPrice") BigDecimal highestPrice,
                         @Param("lowestPrice") BigDecimal lowestPrice,
                         @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = 'CLOSE_SENT', exit_reason = #{exitReason}, "
            + "close_client_order_id = #{clientOrderId}, close_order_id = #{orderId}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markCloseAccepted(@Param("id") long id,
                          @Param("exitReason") String exitReason,
                          @Param("clientOrderId") String clientOrderId,
                          @Param("orderId") Long orderId,
                          @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = 'DONE', close_filled_contracts = #{filledContracts}, "
            + "close_avg_price = #{avgPrice}, close_fee = #{fee}, realized_profit = #{realizedProfit}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markCloseFilled(@Param("id") long id,
                        @Param("filledContracts") BigDecimal filledContracts,
                        @Param("avgPrice") BigDecimal avgPrice,
                        @Param("fee") BigDecimal fee,
                        @Param("realizedProfit") BigDecimal realizedProfit,
                        @Param("message") String message);

    @Update("UPDATE htx_trend_position SET status = #{nextStatus}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markStatus(@Param("id") long id,
                   @Param("nextStatus") String nextStatus,
                   @Param("message") String message);

    @Select("SELECT MAX(updated_at) FROM htx_trend_position WHERE contract_code = #{contractCode} AND status = 'DONE'")
    LocalDateTime latestDoneAt(@Param("contractCode") String contractCode);
}
