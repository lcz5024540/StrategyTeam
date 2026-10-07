package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStructureLifecycleEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HtxTrendStructureLifecycleMapper extends BaseMapper<HtxTrendStructureLifecycleEntity> {

    @Select("SELECT * FROM htx_trend_structure_lifecycle WHERE structure_key = #{structureKey} LIMIT 1")
    HtxTrendStructureLifecycleEntity findByStructureKey(@Param("structureKey") String structureKey);

    @Insert("INSERT IGNORE INTO htx_trend_structure_lifecycle (structure_key, contract_code, timeframe, direction, "
            + "primary_model, breakout_bar_time, state, source_run_id, first_seen_at) VALUES "
            + "(#{structureKey}, #{contractCode}, #{timeframe}, #{direction}, #{primaryModel}, #{breakoutBarTime}, "
            + "'DISCOVERED', #{sourceRunId}, CURRENT_TIMESTAMP(3))")
    int insertDiscovered(HtxTrendStructureLifecycleEntity entity);

    @Update("UPDATE htx_trend_structure_lifecycle SET state = 'DISCOVERED', source_run_id = #{sourceRunId}, "
            + "entry_position_id = NULL, terminal_position_id = NULL, terminal_reason = NULL, opened_at = NULL, "
            + "terminal_at = NULL, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE structure_key = #{structureKey} "
            + "AND state IN ('ABANDONED', 'EXHAUSTED', 'INVALIDATED', 'PROFIT_PROTECTED', 'SUPERSEDED')")
    int reclaimReusable(@Param("structureKey") String structureKey, @Param("sourceRunId") String sourceRunId);

    @Update("UPDATE htx_trend_structure_lifecycle SET entry_position_id = #{positionId}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE structure_key = #{structureKey} "
            + "AND source_run_id = #{sourceRunId} AND state = 'DISCOVERED'")
    int attachIntent(@Param("structureKey") String structureKey, @Param("sourceRunId") String sourceRunId,
                     @Param("positionId") long positionId);

    @Update("UPDATE htx_trend_structure_lifecycle SET state = 'ACTIVE', opened_at = COALESCE(opened_at, CURRENT_TIMESTAMP(3)), "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE entry_position_id = #{positionId} "
            + "AND state = 'DISCOVERED'")
    int markEntryActive(@Param("positionId") long positionId);

    @Update("UPDATE htx_trend_structure_lifecycle SET state = 'ABANDONED', terminal_reason = #{reason}, "
            + "terminal_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE entry_position_id = #{positionId} AND state = 'DISCOVERED'")
    int markEntryAbandoned(@Param("positionId") long positionId, @Param("reason") String reason);

    @Update("UPDATE htx_trend_structure_lifecycle SET state = #{state}, terminal_position_id = #{positionId}, "
            + "terminal_reason = #{reason}, terminal_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE entry_position_id = #{positionId} AND state IN ('DISCOVERED', 'ACTIVE', 'PROFIT_PROTECTED')")
    int markTerminal(@Param("positionId") long positionId, @Param("state") String state, @Param("reason") String reason);
}
