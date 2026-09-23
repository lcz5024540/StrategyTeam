package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Frozen public market candle used by exactly one replay manifest. */
@Data
@TableName("htx_trend_replay_candle")
public class HtxTrendReplayCandleEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String replayRunId;
    private String contractCode;
    private String period;
    private Long barTime;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal volume;
    private BigDecimal turnover;
    private Long tradeCount;
    private LocalDateTime createdAt;
}
