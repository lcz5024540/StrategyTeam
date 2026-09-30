package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable public-market candle version observed by a running trend calculation. */
@Data
@TableName("htx_trend_runtime_kline_candle")
public class HtxTrendRuntimeKlineCandleEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String candleHash;
    private String contractCode;
    private String period;
    private Long barTime;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal closePrice;
    private BigDecimal amount;
    private BigDecimal volume;
    private BigDecimal turnover;
    private Long tradeCount;
    private String sourceKind;
    private LocalDateTime capturedAt;
}
