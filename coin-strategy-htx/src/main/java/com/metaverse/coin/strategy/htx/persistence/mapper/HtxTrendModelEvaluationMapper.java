package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendModelEvaluationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendModelEvaluationMapper extends BaseMapper<HtxTrendModelEvaluationEntity> {

    @Select("SELECT * FROM htx_trend_model_evaluation WHERE evaluation_key = #{evaluationKey}")
    HtxTrendModelEvaluationEntity findByEvaluationKey(@Param("evaluationKey") String evaluationKey);
}
