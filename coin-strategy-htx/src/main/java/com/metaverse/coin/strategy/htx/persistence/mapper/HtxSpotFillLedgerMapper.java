package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxSpotFillLedgerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxSpotFillLedgerMapper extends BaseMapper<HtxSpotFillLedgerEntity> {

    @Select("SELECT COUNT(*) FROM htx_spot_fill_ledger "
            + "WHERE side = #{side} AND symbol = #{symbol} AND htx_order_id = #{htxOrderId}")
    long countByOrder(@Param("side") String side,
                      @Param("symbol") String symbol,
                      @Param("htxOrderId") Long htxOrderId);

    @Select("""
            <script>
            SELECT *
            FROM htx_spot_fill_ledger
            WHERE symbol = #{symbol}
              AND side = #{side}
              <choose>
                <when test="htxOrderId != null">
                  AND htx_order_id = #{htxOrderId}
                </when>
                <otherwise>
                  AND client_order_id = #{clientOrderId}
                </otherwise>
              </choose>
            ORDER BY id DESC
            LIMIT 1
            </script>
            """)
    HtxSpotFillLedgerEntity findLatestSnapshot(@Param("symbol") String symbol,
                                               @Param("side") String side,
                                               @Param("htxOrderId") Long htxOrderId,
                                               @Param("clientOrderId") String clientOrderId);
}
