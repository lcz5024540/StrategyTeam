package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendReplayRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface HtxTrendReplayRunMapper extends BaseMapper<HtxTrendReplayRunEntity> {

    @Update("UPDATE htx_trend_replay_run SET status = #{status}, input_integrity = #{inputIntegrity}, "
            + "candidate_count = #{candidateCount}, trade_count = #{tradeCount}, comparable_trade_count = #{comparableTradeCount}, "
            + "net_profit = #{netProfit}, profit_factor = #{profitFactor}, message = #{message}, "
            + "completed_at = CURRENT_TIMESTAMP(3) WHERE replay_run_id = #{replayRunId}")
    int complete(@Param("replayRunId") String replayRunId, @Param("status") String status,
                 @Param("inputIntegrity") String inputIntegrity, @Param("candidateCount") long candidateCount,
                 @Param("tradeCount") long tradeCount, @Param("comparableTradeCount") long comparableTradeCount,
                 @Param("netProfit") BigDecimal netProfit, @Param("profitFactor") BigDecimal profitFactor,
                 @Param("message") String message);
}
