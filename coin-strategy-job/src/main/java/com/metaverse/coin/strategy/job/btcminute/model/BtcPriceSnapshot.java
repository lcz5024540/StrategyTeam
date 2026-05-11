package com.metaverse.coin.strategy.job.btcminute.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * BTC 实时价格快照。
 */
@Data
@Builder
public class BtcPriceSnapshot {

    /** 当前价格 */
    private BigDecimal price;

    /** 24 小时交易量（BTC） */
    private BigDecimal volume24h;

    /** 1 分钟 K 线开盘价 */
    private BigDecimal klineOpen;

    /** 1 分钟 K 线最高价 */
    private BigDecimal klineHigh;

    /** 1 分钟 K 线最低价 */
    private BigDecimal klineLow;

    /** 1 分钟 K 线收盘价 */
    private BigDecimal klineClose;

    /** K 线成交量 */
    private BigDecimal klineVolume;

    /** 快照时间 */
    private Instant timestamp;

    /** 数据来源: WEBSOCKET / REST */
    private String source;
}
