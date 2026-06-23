package com.metaverse.coin.strategy.job.btcminute.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * BTC 5 分钟涨跌策略信号。
 */
@Data
@Builder
public class BtcMinuteSignal {

    /** Polymarket conditionId */
    private String marketId;

    /** 市场标题 */
    private String marketTitle;

    /** YES / NO */
    private String outcome;

    /** 当前 BTC 价格 */
    private BigDecimal btcPrice;

    /** 模型计算的公允概率 */
    private BigDecimal fairProbability;

    /** Polymarket 当前市场价格 */
    private BigDecimal marketPrice;

    /** Edge = |fairProbability - marketPrice| */
    private BigDecimal edge;

    /** 建议仓位（USDC） */
    private BigDecimal suggestedStakeUsdc;

    /** 信号置信度 [0, 1] */
    private BigDecimal confidence;

    /** 市场结算时间 */
    private Instant settlementTime;

    /** 距离结算的分钟数 */
    private int minutesToSettlement;

    /** 信号产生时间 */
    private Instant signalTime;

    /** RSI 值 */
    private BigDecimal rsi;

    /** EMA 快慢线差值 */
    private BigDecimal emaDiff;

    /** 短期波动率 */
    private BigDecimal volatility;
}
