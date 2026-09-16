package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface HtxTrendSignalMapper extends BaseMapper<HtxTrendSignalEntity> {

    @Select("SELECT COUNT(*) FROM ("
            + "SELECT trend_signal FROM htx_trend_signal "
            + "WHERE contract_code = #{contractCode} AND created_at >= #{since} "
            + "ORDER BY id DESC LIMIT #{limit}"
            + ") recent WHERE recent.trend_signal = #{signal}")
    Long countRecentSignalsBySide(@Param("contractCode") String contractCode,
                                  @Param("signal") String signal,
                                  @Param("since") LocalDateTime since,
                                  @Param("limit") int limit);
}
