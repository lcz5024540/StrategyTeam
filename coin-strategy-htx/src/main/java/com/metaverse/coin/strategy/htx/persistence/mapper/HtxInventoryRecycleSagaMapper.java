package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface HtxInventoryRecycleSagaMapper extends BaseMapper<HtxInventoryRecycleSagaEntity> {

    String COLUMNS = "id, saga_key, symbol, contract_code, epoch, spot_fill_ledger_id, spot_sold_base, "
            + "available_short_base, close_contracts, close_client_order_id, planner_state, status, reason, "
            + "created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM htx_inventory_recycle_saga WHERE saga_key = #{sagaKey} LIMIT 1")
    HtxInventoryRecycleSagaEntity findBySagaKey(@Param("sagaKey") String sagaKey);

    @Select("SELECT " + COLUMNS + " FROM htx_inventory_recycle_saga "
            + "WHERE status = #{status} ORDER BY id LIMIT #{limit}")
    List<HtxInventoryRecycleSagaEntity> findByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT " + COLUMNS + " FROM htx_inventory_recycle_saga WHERE id = #{id} LIMIT 1")
    HtxInventoryRecycleSagaEntity findById(@Param("id") long id);

    @Update("UPDATE htx_inventory_recycle_saga SET status = #{nextStatus}, reason = #{reason}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusIfCurrent(@Param("id") long id,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("nextStatus") String nextStatus,
                              @Param("reason") String reason);
}
