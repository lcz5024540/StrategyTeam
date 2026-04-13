package com.metaverse.coin.strategy.job.hedge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Polymarket 对冲策略独立配置
 *
 * 命名空间 polymarket.hedge 与跟单策略 smart-wallet 完全隔离，
 * 互不影响：修改此配置不会对跟单策略产生任何副作用。
 */
@Data
@ConfigurationProperties(prefix = "polymarket.hedge")
public class HedgeProperties {

    /** 对冲策略总开关 */
    private boolean enabled = false;

    /** 自动下单开关（false = 只扫描/记录，不实际下单）*/
    private boolean autoExecutionEnabled = false;

    // ─────────────────────────────────────────────
    // 套利机会识别
    // ─────────────────────────────────────────────

    /** 最小概率偏差（毛利率）阈值：低于此值不开仓 */
    private BigDecimal minGrossEdgeRate = new BigDecimal("0.025");

    /** 扣除预估费用后的最小净利率阈值 */
    private BigDecimal minNetEdgeRate = new BigDecimal("0.015");

    /** 单边最小流动性（USDC）：两侧均需满足 */
    private BigDecimal minSideLiquidityUsdc = new BigDecimal("2000");

    /** 市场扫描间隔（ms），默认 10 秒 */
    private Long scanFixedDelayMs = 10_000L;

    /** 单次扫描最多处理市场数 */
    private Integer scanBatchSize = 50;

    // ─────────────────────────────────────────────
    // 仓位与资金管理
    // ─────────────────────────────────────────────

    /** 单笔最大投入 USDC */
    private BigDecimal maxSinglePositionUsdc = new BigDecimal("3000");

    /** 单笔最小投入 USDC */
    private BigDecimal minSinglePositionUsdc = new BigDecimal("50");

    /** 对冲策略最大同时持仓数 */
    private int maxOpenPositions = 20;

    /** 对冲策略最大总敞口 USDC */
    private BigDecimal maxTotalExposureUsdc = new BigDecimal("30000");

    /**
     * Kelly 比例折扣因子（0.5 = 半 Kelly，更保守）
     * 实际投入 = Kelly 计算值 × kellyFraction
     */
    private BigDecimal kellyFraction = new BigDecimal("0.5");

    // ─────────────────────────────────────────────
    // 执行参数
    // ─────────────────────────────────────────────

    /** 预估手续费率（双边各收一次，默认 0.15%）*/
    private BigDecimal estimatedFeeRate = new BigDecimal("0.0015");

    /** 下单价格保护：允许相对 best ask 的最大滑点比例 */
    private BigDecimal maxSlippageRate = new BigDecimal("0.005");

    /** 订单类型（GTC = 好至取消）*/
    private String orderType = "GTC";

    // ─────────────────────────────────────────────
    // 结算监控
    // ─────────────────────────────────────────────

    /** 结算监控轮询间隔（ms），默认 30 秒 */
    private Long settlementFixedDelayMs = 30_000L;

    /** 单次结算检查最多处理持仓数 */
    private int settlementBatchSize = 30;

    // ─────────────────────────────────────────────
    // 跨策略全局风控
    // ─────────────────────────────────────────────

    /**
     * 全局最大 USDC 敞口（跟单 + 对冲合计）
     * 0 = 不启用此限制
     */
    private BigDecimal globalMaxTotalExposureUsdc = new BigDecimal("80000");

    /**
     * 对冲策略占全局敞口的最大比例（0.5 = 最多占 50%）
     * 防止对冲策略挤占跟单策略的资金
     */
    private BigDecimal maxHedgeShareOfGlobalExposure = new BigDecimal("0.5");
}
