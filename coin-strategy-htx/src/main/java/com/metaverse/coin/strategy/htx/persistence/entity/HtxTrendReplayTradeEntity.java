package com.metaverse.coin.strategy.htx.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One replayed source candidate/position, including unavailable evidence rather than invented outcomes. */
@Data
@TableName("htx_trend_replay_trade")
public class HtxTrendReplayTradeEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String replayRunId;
    private Long sourceCandidateId;
    private Long sourcePositionId;
    private String sourceRunId;
    private String contractCode;
    private String period;
    private String direction;
    private String marketRegime;
    private String primaryModel;
    private Long entryBarTime;
    private BigDecimal entryPrice;
    private Long exitBarTime;
    private BigDecimal exitPrice;
    private Long initialContracts;
    private Long remainingContracts;
    private BigDecimal mfe;
    private BigDecimal mae;
    private BigDecimal entryFee;
    private BigDecimal closeFee;
    private BigDecimal netProfit;
    private String outcomeState;
    private String exitReason;
    private String dataIntegrity;
    private String ambiguityReason;
    private String message;
    private LocalDateTime createdAt;
}
