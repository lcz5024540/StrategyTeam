package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Latest computed trend-following signal snapshot for observation and audit. */
@Data
@TableName("htx_trend_signal")
public class HtxTrendSignalEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractCode;
    private String period;
    private Long barTime;
    @TableField("trend_signal")
    private String signal;
    private BigDecimal closePrice;
    private BigDecimal emaFast;
    private BigDecimal emaSlow;
    private BigDecimal breakoutHigh;
    private BigDecimal breakoutLow;
    private BigDecimal atr;
    private BigDecimal atrPct;
    private BigDecimal adx;
    private BigDecimal spreadRate;
    private BigDecimal fundingRate;
    private String reason;
    private LocalDateTime createdAt;
}
