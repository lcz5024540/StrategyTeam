package com.metaverse.coin.strategy.job.engine.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 策略产生的交易信号，统一格式。
 * 不同策略的信号来源不同，但最终都规范为此结构。
 */
@Data
@Builder
public class TradingSignal {

    /** 策略标识 */
    private String strategyId;

    /** Polymarket conditionId */
    private String marketId;

    /** YES / NO */
    private String outcome;

    /** BUY / SELL */
    private String side;

    /** 信号价格 */
    private BigDecimal targetPrice;

    /** 建议仓位（USDC） */
    private BigDecimal stakeUsdc;

    /** 信号置信度 [0, 1] */
    private BigDecimal confidence;

    /** 信号来源描述 */
    private String signalReason;

    /** 信号产生时间 */
    private Instant signalTime;

    /** 策略特有的附加信息 */
    private Map<String, Object> metadata;
}
