package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchLabelEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HtxTrendResearchLabelMapper extends BaseMapper<HtxTrendResearchLabelEntity> {

    @Select("SELECT * FROM htx_trend_research_label WHERE label_key = #{labelKey}")
    HtxTrendResearchLabelEntity findByLabelKey(@Param("labelKey") String labelKey);
}
