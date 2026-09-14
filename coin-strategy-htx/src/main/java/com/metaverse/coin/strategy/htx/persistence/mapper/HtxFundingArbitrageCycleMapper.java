package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxFundingArbitrageCycleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface HtxFundingArbitrageCycleMapper extends BaseMapper<HtxFundingArbitrageCycleEntity> {

    String COLUMNS = "id, cycle_key, symbol, contract_code, status, target_quote_notional, "
            + "target_base_amount, contract_size, contract_volume, leverage, entry_funding_rate, "
            + "latest_funding_rate, entry_spot_price, entry_swap_price, spot_entry_client_order_id, "
            + "spot_entry_order_id, spot_entry_filled_base, spot_entry_filled_quote, spot_entry_fee_base, "
            + "short_entry_client_order_id, short_entry_order_id, short_entry_filled_contracts, "
            + "short_entry_avg_price, short_entry_fee, short_close_client_order_id, short_close_order_id, "
            + "short_close_filled_contracts, short_close_avg_price, short_close_fee, "
            + "spot_exit_client_order_id, spot_exit_order_id, spot_exit_filled_base, "
            + "spot_exit_filled_quote, spot_exit_fee_quote, exit_reason, message, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_funding_arbitrage_cycle "
            + "WHERE symbol = #{symbol} AND contract_code = #{contractCode} "
            + "AND status IN ('INTENT', 'SPOT_SENT', 'SPOT_FILLED', 'SHORT_SENT', 'SHORT_DEGRADED', "
            + "'ACTIVE', 'SHORT_CLOSED', 'EXITING_SHORT_SENT', 'EXITING_SPOT_SENT', 'UNKNOWN') "
            + "ORDER BY id LIMIT 1")
    HtxFundingArbitrageCycleEntity findActive(@Param("symbol") String symbol,
                                               @Param("contractCode") String contractCode);

    @Select("SELECT " + COLUMNS + " FROM htx_funding_arbitrage_cycle WHERE id = #{id} LIMIT 1")
    HtxFundingArbitrageCycleEntity findById(@Param("id") long id);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIfCurrent(@Param("id") long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("nextStatus") String nextStatus,
                              @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, "
            + "spot_entry_order_id = #{orderId}, message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateSpotEntryAcceptedIfCurrent(@Param("id") long id,
                                         @Param("expectedStatus") String expectedStatus,
                                         @Param("nextStatus") String nextStatus,
                                         @Param("orderId") Long orderId,
                                         @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, latest_funding_rate = #{latestFundingRate}, "
            + "spot_entry_filled_base = #{filledBase}, spot_entry_filled_quote = #{filledQuote}, "
            + "spot_entry_fee_base = #{feeBase}, message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id}")
    int updateSpotEntryFill(@Param("id") long id,
                            @Param("nextStatus") String nextStatus,
                            @Param("latestFundingRate") BigDecimal latestFundingRate,
                            @Param("filledBase") BigDecimal filledBase,
                            @Param("filledQuote") BigDecimal filledQuote,
                            @Param("feeBase") BigDecimal feeBase,
                            @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, contract_volume = #{contractVolume}, "
            + "short_entry_client_order_id = #{clientOrderId}, short_entry_order_id = #{orderId}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateShortEntryAccepted(@Param("id") long id,
                                 @Param("nextStatus") String nextStatus,
                                 @Param("contractVolume") Long contractVolume,
                                 @Param("clientOrderId") String clientOrderId,
                                 @Param("orderId") Long orderId,
                                 @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, "
            + "short_entry_filled_contracts = #{filledContracts}, short_entry_avg_price = #{avgPrice}, "
            + "short_entry_fee = #{fee}, latest_funding_rate = #{latestFundingRate}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateShortEntryFill(@Param("id") long id,
                             @Param("nextStatus") String nextStatus,
                             @Param("filledContracts") BigDecimal filledContracts,
                             @Param("avgPrice") BigDecimal avgPrice,
                             @Param("fee") BigDecimal fee,
                             @Param("latestFundingRate") BigDecimal latestFundingRate,
                             @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, exit_reason = #{exitReason}, "
            + "short_close_client_order_id = #{clientOrderId}, short_close_order_id = #{orderId}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateShortCloseAccepted(@Param("id") long id,
                                 @Param("nextStatus") String nextStatus,
                                 @Param("exitReason") String exitReason,
                                 @Param("clientOrderId") String clientOrderId,
                                 @Param("orderId") Long orderId,
                                 @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, "
            + "short_close_filled_contracts = #{filledContracts}, short_close_avg_price = #{avgPrice}, "
            + "short_close_fee = #{fee}, message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id}")
    int updateShortCloseFill(@Param("id") long id,
                             @Param("nextStatus") String nextStatus,
                             @Param("filledContracts") BigDecimal filledContracts,
                             @Param("avgPrice") BigDecimal avgPrice,
                             @Param("fee") BigDecimal fee,
                             @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, "
            + "spot_exit_client_order_id = #{clientOrderId}, spot_exit_order_id = #{orderId}, "
            + "message = #{message}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int updateSpotExitAccepted(@Param("id") long id,
                               @Param("nextStatus") String nextStatus,
                               @Param("clientOrderId") String clientOrderId,
                               @Param("orderId") Long orderId,
                               @Param("message") String message);

    @Update("UPDATE htx_funding_arbitrage_cycle SET status = #{nextStatus}, "
            + "spot_exit_filled_base = #{filledBase}, spot_exit_filled_quote = #{filledQuote}, "
            + "spot_exit_fee_quote = #{feeQuote}, message = #{message}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id}")
    int updateSpotExitFill(@Param("id") long id,
                           @Param("nextStatus") String nextStatus,
                           @Param("filledBase") BigDecimal filledBase,
                           @Param("filledQuote") BigDecimal filledQuote,
                           @Param("feeQuote") BigDecimal feeQuote,
                           @Param("message") String message);
}
