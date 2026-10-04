package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendPaperExecutionEventMapper extends BaseMapper<HtxTrendPaperExecutionEventEntity> {

    @Select("SELECT id, event_key, execution_key, run_id, state_from, state_to, event_type, requested_contracts, "
            + "cumulative_filled_contracts, fill_price, reason, occurred_at, created_at "
            + "FROM htx_trend_paper_execution_event WHERE event_key = #{eventKey} LIMIT 1")
    HtxTrendPaperExecutionEventEntity findByEventKey(@Param("eventKey") String eventKey);

    @Select("SELECT id, event_key, execution_key, run_id, state_from, state_to, event_type, requested_contracts, "
            + "cumulative_filled_contracts, fill_price, reason, occurred_at, created_at "
            + "FROM htx_trend_paper_execution_event WHERE execution_key = #{executionKey} "
            + "ORDER BY id DESC LIMIT 1")
    HtxTrendPaperExecutionEventEntity findLatestByExecutionKey(@Param("executionKey") String executionKey);
}
