package com.metaverse.coin.strategy.htx.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 做市策略配置，绑定 {@code application.yml} 的 {@code htx.market-maker.*}。
 * 权威值在配置文件（与现有风控约定一致），Java 仅声明兜底默认。
 * 见设计 spec 2026-08-06-htx-market-maker-design.md §5。
 */
@Data
@ConfigurationProperties(prefix = "htx.market-maker")
public class HtxMarketMakerProperties {

    /** 总开关，默认关；急停语义 = 关闭后重启不加载调度器，不开新 cell。 */
    private boolean enabled = false;

    /** 标的白名单，非白名单一律拒开。 */
    private List<String> symbols = List.of("btcusdt", "ethusdt", "xautusdt");

    /** 主循环轮询间隔（ms）。 */
    private long pollIntervalMs = 10_000L;

    /** 价差 δ：buyPrice=P0(1−δ)，sellPrice=P0(1+δ)。 */
    private BigDecimal spreadPct = new BigDecimal("0.003");

    /** 保守单边费率 f，用于不亏闸门（宁高勿低），不查账户真实费率。 */
    private BigDecimal feeRatePerSide = new BigDecimal("0.002");

    /** 每 cell 固定 quote 名义（USDT）；qty = notional / buyPrice。 */
    private BigDecimal perCellQuoteNotional = new BigDecimal("20");

    /** 单标的最大并行 open cell 数 K。 */
    private int maxConcurrentCellsPerSymbol = 3;

    /** 新买价与已挂买价的最小相对间距（去重防同价堆单）。 */
    private BigDecimal minGapPct = new BigDecimal("0.003");

    /** 买单未成交超时撤单（ms）。 */
    private long buyTtlMs = 900_000L;

    /** 卖单 TTL（ms）；0 = 不设 TTL，一直挂到成交。 */
    private long sellTtlMs = 0L;

    /** 买价漂移超此相对比例（|bestBid−buyPrice|/bestBid）即撤旧买单、在新 bestBid 重挂（跟价）。 */
    private BigDecimal repriceThresholdPct = new BigDecimal("0.001");

    /** 可用 USDT 不足时暂停所有新买单的时长（ms），到期后重新查余额自动恢复。 */
    private long insufficientBalanceBackoffMs = 300_000L;

    /** 卖腿跟价+止损总开关；false=退回旧「固定价挂到成交」（config-only 回退）。 */
    private boolean sellFollowEnabled = true;

    /** 跌破 buy·(1−此值) 触发 taker 割肉。 */
    private BigDecimal stopLossPct = new BigDecimal("0.005");

    /** 卖价漂移超此比例才重挂（防抖、省撤单费）。 */
    private BigDecimal sellRepriceThresholdPct = new BigDecimal("0.001");

    /** 卖单挂太久 → 保本(容忍微亏)吃单退出 总开关；false=完全关闭本功能（config-only 回退）。 */
    private boolean sellLingerExitEnabled = true;

    /** 卖单在 RESTING_SELL 挂满此时长（ms）才触发 linger taker 退出。 */
    private long sellLingerTimeoutMs = 120_000L;

    /** linger 退出容忍亏损比例：(1−f)²·bestBid ≥ buy·(1−此值) 即可吃单。 */
    private BigDecimal sellLingerLossTolerancePct = new BigDecimal("0.002");
}
