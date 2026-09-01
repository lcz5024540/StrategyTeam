package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface HtxDeltaHedgeLedgerMapper extends BaseMapper<HtxDeltaHedgeLedgerEntity> {

    String COLUMNS = "id, hedge_key, symbol, contract_code, epoch, action, target_short_base, "
            + "contract_volume, side, position_side, reduce_only, client_order_id, planner_state, "
            + "status, reason, created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_delta_hedge_ledger WHERE hedge_key = #{hedgeKey} LIMIT 1")
    HtxDeltaHedgeLedgerEntity findByHedgeKey(@Param("hedgeKey") String hedgeKey);

    @Select("SELECT " + COLUMNS + " FROM htx_delta_hedge_ledger "
            + "WHERE status = #{status} ORDER BY id LIMIT #{limit}")
    List<HtxDeltaHedgeLedgerEntity> findByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT " + COLUMNS + " FROM htx_delta_hedge_ledger WHERE id = #{id} LIMIT 1")
    HtxDeltaHedgeLedgerEntity findById(@Param("id") long id);

    @Update("UPDATE htx_delta_hedge_ledger SET status = #{nextStatus}, reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIfCurrent(@Param("id") long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("nextStatus") String nextStatus,
                              @Param("reason") String reason);
}
