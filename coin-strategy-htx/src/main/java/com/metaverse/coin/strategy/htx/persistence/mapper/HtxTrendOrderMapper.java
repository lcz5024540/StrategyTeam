package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface HtxTrendOrderMapper extends BaseMapper<HtxTrendOrderEntity> {

    @Select("SELECT * FROM htx_trend_order WHERE position_id = #{positionId} AND leg = 'CLOSE' "
            + "AND status = 'ACCEPTED' ORDER BY id DESC LIMIT 1")
    HtxTrendOrderEntity findLatestAcceptedCloseOrder(@Param("positionId") long positionId);

    /** Latest full-close intent regardless of whether it was accepted by HTX. */
    @Select("SELECT * FROM htx_trend_order WHERE position_id = #{positionId} AND leg = 'CLOSE' "
            + "ORDER BY id DESC LIMIT 1")
    HtxTrendOrderEntity findLatestCloseOrder(@Param("positionId") long positionId);

    @Update("UPDATE htx_trend_order SET status = #{status}, exchange_order_id = #{exchangeOrderId}, "
            + "exchange_client_order_id = #{exchangeClientOrderId}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE position_id = #{positionId} AND leg = #{leg} "
            + "AND client_order_id = #{clientOrderId}")
    int markAccepted(@Param("positionId") long positionId,
                     @Param("leg") String leg,
                     @Param("clientOrderId") String clientOrderId,
                     @Param("status") String status,
                     @Param("exchangeOrderId") Long exchangeOrderId,
                     @Param("exchangeClientOrderId") Long exchangeClientOrderId,
                     @Param("message") String message);

    @Select("SELECT * FROM htx_trend_order WHERE position_id = #{positionId} AND leg = 'PARTIAL_CLOSE' "
            + "AND status = 'ACCEPTED' ORDER BY id DESC LIMIT 1")
    HtxTrendOrderEntity findLatestAcceptedPartialCloseOrder(@Param("positionId") long positionId);

    @Update("UPDATE htx_trend_order SET status = #{status}, exchange_order_id = #{exchangeOrderId}, "
            + "exchange_client_order_id = #{exchangeClientOrderId}, exchange_state = #{exchangeState}, "
            + "filled_contracts = #{filledContracts}, average_price = #{averagePrice}, fee = #{fee}, "
            + "fee_currency = #{feeCurrency}, realized_profit = #{realizedProfit}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE position_id = #{positionId} AND leg = #{leg} "
            + "AND client_order_id = #{clientOrderId}")
    int markReconciled(@Param("positionId") long positionId,
                       @Param("leg") String leg,
                       @Param("clientOrderId") String clientOrderId,
                       @Param("status") String status,
                       @Param("exchangeOrderId") Long exchangeOrderId,
                       @Param("exchangeClientOrderId") Long exchangeClientOrderId,
                       @Param("exchangeState") String exchangeState,
                       @Param("filledContracts") BigDecimal filledContracts,
                       @Param("averagePrice") BigDecimal averagePrice,
                       @Param("fee") BigDecimal fee,
                       @Param("feeCurrency") String feeCurrency,
                       @Param("realizedProfit") BigDecimal realizedProfit,
                       @Param("message") String message);

    @Update("UPDATE htx_trend_order SET status = #{status}, message = #{message}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE position_id = #{positionId} AND leg = #{leg} "
            + "AND client_order_id = #{clientOrderId}")
    int markStatus(@Param("positionId") long positionId,
                   @Param("leg") String leg,
                   @Param("clientOrderId") String clientOrderId,
                   @Param("status") String status,
                   @Param("message") String message);
}
