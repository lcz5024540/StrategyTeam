package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable manifest and summary for one reproducible HTX trend replay. */
@Data
@TableName("htx_trend_replay_run")
public class HtxTrendReplayRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String replayRunId;
    private String engineVersion;
    private LocalDateTime inputStartAt;
    private LocalDateTime inputEndAt;
    private String periods;
    private String status;
    private String inputIntegrity;
    private Long candidateCount;
    private Long tradeCount;
    private Long comparableTradeCount;
    private BigDecimal netProfit;
    private BigDecimal profitFactor;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
