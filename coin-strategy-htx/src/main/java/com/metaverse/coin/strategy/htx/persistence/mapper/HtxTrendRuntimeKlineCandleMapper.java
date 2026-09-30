package com.metaverse.coin.strategy.htx.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineCandleEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HtxTrendRuntimeKlineCandleMapper extends BaseMapper<HtxTrendRuntimeKlineCandleEntity> {

    /** Content-addressed writes retain a revised public bar while ignoring exact repeats. */
    @Insert("<script>"
            + "INSERT IGNORE INTO htx_trend_runtime_kline_candle (candle_hash, contract_code, period, bar_time, "
            + "open_price, high_price, low_price, close_price, amount, volume, turnover, trade_count, source_kind, captured_at) VALUES "
            + "<foreach collection='candles' item='candle' separator=','>"
            + "(#{candle.candleHash}, #{candle.contractCode}, #{candle.period}, #{candle.barTime}, "
            + "#{candle.openPrice}, #{candle.highPrice}, #{candle.lowPrice}, #{candle.closePrice}, #{candle.amount}, "
            + "#{candle.volume}, #{candle.turnover}, #{candle.tradeCount}, #{candle.sourceKind}, #{candle.capturedAt})"
            + "</foreach>"
            + "</script>")
    int insertIgnoreBatch(@Param("candles") List<HtxTrendRuntimeKlineCandleEntity> candles);
}
