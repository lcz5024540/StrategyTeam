package com.metaverse.coin.strategy.job.btcminute.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Polymarket 上的 BTC 短期市场规格。
 * 描述一个 "BTC 价格在 X 分钟内 > Y" 之类的市场。
 */
@Data
@Builder
public class BtcMinuteMarketSpec {

    /** Polymarket conditionId */
    private String conditionId;

    /** YES token ID */
    private String yesTokenId;

    /** NO token ID */
    private String noTokenId;

    /** 市场标题 */
    private String title;

    /** 目标价格（如 BTC > $100,000） */
    private BigDecimal targetPrice;

    /** 预测方向: UP / DOWN */
    private String direction;

    /** 时间窗口（分钟） */
    private int windowMinutes;

    /** 结算时间 */
    private Instant settlementTime;

    /** 当前 YES 价格 */
    private BigDecimal yesPrice;

    /** 当前 NO 价格 */
    private BigDecimal noPrice;

    /** 3 cent ask depth (USDC) */
    private BigDecimal askDepth3cUsdc;

    /** Bid-Ask spread (bps) */
    private BigDecimal spreadBps;

    /** 品类标签 */
    private String category;
}
