package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Frozen market candle that belongs to a research dataset rather than a live order. */
@Data
@TableName("htx_trend_research_candle")
public class HtxTrendResearchCandleEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String datasetKey;
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
    private String sourceKind;
    private LocalDateTime createdAt;
}
