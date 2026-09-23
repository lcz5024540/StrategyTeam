package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendReplayCandleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HtxTrendReplayCandleMapper extends BaseMapper<HtxTrendReplayCandleEntity> {

    @Select("SELECT id, replay_run_id, contract_code, period, bar_time, open_price, high_price, low_price, "
            + "close_price, volume, turnover, trade_count, created_at FROM htx_trend_replay_candle "
            + "WHERE replay_run_id = #{replayRunId} AND contract_code = #{contractCode} AND period = #{period} "
            + "ORDER BY bar_time")
    List<HtxTrendReplayCandleEntity> findByReplayRun(@Param("replayRunId") String replayRunId,
                                                      @Param("contractCode") String contractCode,
                                                      @Param("period") String period);
}
