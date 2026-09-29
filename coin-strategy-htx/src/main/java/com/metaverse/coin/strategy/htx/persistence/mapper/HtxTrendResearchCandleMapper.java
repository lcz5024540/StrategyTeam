package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchCandleEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HtxTrendResearchCandleMapper extends BaseMapper<HtxTrendResearchCandleEntity> {

    @Insert("<script>"
            + "INSERT INTO htx_trend_research_candle (dataset_key, contract_code, period, bar_time, open_price, high_price, "
            + "low_price, close_price, volume, turnover, trade_count, source_kind) VALUES "
            + "<foreach collection='candles' item='candle' separator=','>"
            + "(#{candle.datasetKey}, #{candle.contractCode}, #{candle.period}, #{candle.barTime}, #{candle.openPrice}, "
            + "#{candle.highPrice}, #{candle.lowPrice}, #{candle.closePrice}, #{candle.volume}, #{candle.turnover}, "
            + "#{candle.tradeCount}, #{candle.sourceKind})"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("candles") List<HtxTrendResearchCandleEntity> candles);
}
