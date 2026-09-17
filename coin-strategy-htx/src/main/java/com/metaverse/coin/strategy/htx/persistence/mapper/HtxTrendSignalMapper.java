package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendSignalEntity;
import com.metaverse.coin.strategy.htx.persistence.model.HtxTrendBreadthStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface HtxTrendSignalMapper extends BaseMapper<HtxTrendSignalEntity> {

    @Select("SELECT COUNT(*) FROM ("
            + "SELECT s.trend_signal FROM htx_trend_signal s "
            + "JOIN ("
            + "SELECT bar_time, MAX(id) AS max_id FROM htx_trend_signal "
            + "WHERE contract_code = #{contractCode} AND created_at >= #{since} AND bar_time IS NOT NULL "
            + "GROUP BY bar_time ORDER BY bar_time DESC LIMIT #{limit}"
            + ") latest_bars ON latest_bars.max_id = s.id"
            + ") recent WHERE recent.trend_signal = #{signal}")
    Long countRecentSignalsBySide(@Param("contractCode") String contractCode,
                                  @Param("signal") String signal,
                                  @Param("since") LocalDateTime since,
                                  @Param("limit") int limit);

    @Select("SELECT COUNT(*) AS totalCount, "
            + "COALESCE(SUM(CASE WHEN latest.trend_signal = 'LONG' "
            + "OR latest.reason LIKE '%trend=LONG%' OR latest.reason LIKE '%uptrend%' "
            + "THEN 1 ELSE 0 END), 0) AS longContextCount, "
            + "COALESCE(SUM(CASE WHEN latest.trend_signal = 'SHORT' "
            + "OR latest.reason LIKE '%trend=SHORT%' OR latest.reason LIKE '%downtrend%' "
            + "THEN 1 ELSE 0 END), 0) AS shortContextCount, "
            + "COALESCE(SUM(CASE WHEN latest.trend_signal = 'LONG' THEN 1 ELSE 0 END), 0) AS longSignalCount, "
            + "COALESCE(SUM(CASE WHEN latest.trend_signal = 'SHORT' THEN 1 ELSE 0 END), 0) AS shortSignalCount "
            + "FROM ("
            + "SELECT s.trend_signal, s.reason FROM htx_trend_signal s "
            + "JOIN ("
            + "SELECT contract_code, MAX(id) AS max_id FROM htx_trend_signal "
            + "WHERE created_at >= #{since} GROUP BY contract_code"
            + ") current_signals ON current_signals.max_id = s.id"
            + ") latest")
    HtxTrendBreadthStats latestBreadthStats(@Param("since") LocalDateTime since);
}
