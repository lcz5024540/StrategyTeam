package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendPaperExecutionMapper extends BaseMapper<HtxTrendPaperExecutionEntity> {

    @Select("SELECT id, execution_key, run_id, contract_code, outcome, fill_price, filled_contracts, reason, created_at "
            + "FROM htx_trend_paper_execution WHERE execution_key = #{executionKey} LIMIT 1")
    HtxTrendPaperExecutionEntity findByExecutionKey(@Param("executionKey") String executionKey);
}
