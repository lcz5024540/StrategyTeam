package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendAssessmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendAssessmentMapper extends BaseMapper<HtxTrendAssessmentEntity> {
    @Select("SELECT trend_state FROM htx_trend_assessment WHERE position_id=#{positionId} "
            + "AND assessment_stage='HOLDING_ASSESSMENT' ORDER BY bar_time DESC, id DESC LIMIT 1")
    String latestHoldingState(@Param("positionId") long positionId);

    @Select("SELECT trend_state FROM htx_trend_assessment WHERE position_id=#{positionId} "
            + "AND assessment_stage='HOLDING_ASSESSMENT' AND bar_time < #{barTime} "
            + "ORDER BY bar_time DESC, id DESC LIMIT 1")
    String latestHoldingStateBefore(@Param("positionId") long positionId, @Param("barTime") long barTime);

    @Select("SELECT COUNT(1) FROM htx_trend_assessment WHERE assessment_key=#{assessmentKey}")
    long countByAssessmentKey(@Param("assessmentKey") String assessmentKey);
}
