package com.metaverse.coin.strategy.common.model.arbitrage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.Date;

/**
 * W1-T4 被拒 / 降级 信号的影子记账.
 *
 * 生命周期:
 *   1. SmartWalletSignalRiskService 决策为 REJECTED / PAPER_SHADOW / DOWNGRADED 时
 *      → 立即写入 entry 部分 (shadow_entry_*), status=PENDING_SETTLEMENT.
 *   2. ShadowSettlementBackfillJob 等市场结算后回填 exit 部分 (shadow_exit_*, shadow_pnl_usdc),
 *      status=SETTLED.
 *   3. 长期未结算或市场异常的 → status=EXPIRED.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T4).
 */
@Data
@Accessors(chain = true)
@TableName("smart_wallet_signal_shadow")
public class SmartWalletSignalShadowEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跟单链路 trace_id, 与 W1-T3 打通. */
    private String traceId;

    /** 关联 follow_task; 硬拒绝(未生成 task)时为 null. */
    private Long followTaskId;

    /** 关联 trade_event.id, 必填. */
    private Long sourceEventId;

    private Long marketInfoId;
    private String walletAddress;

    /** 决策状态: REJECTED / PAPER_SHADOW / DOWNGRADED. */
    private String decisionStatus;

    /** 命中的风控门 (与 firstRejectGate 一致, 如 G1_MANUAL_BLOCKLIST / G7_PRICE_IMPACT). */
    private String rejectLayer;

    /** 拒绝原因列表, "|" 分隔. */
    private String rejectReasons;

    private BigDecimal signalScore;
    private BigDecimal followProbability;

    private String side;                       // BUY/SELL

    // ── 入场模拟 (创建 shadow 时即刻写入) ──
    private BigDecimal shadowEntryPrice;
    private BigDecimal shadowEntrySize;
    private BigDecimal shadowEntryCostUsdc;
    private Integer shadowEntrySlippageBps;

    /**
     * 第十四批: 价格冲击 bps 估算 source — "VWAP(OK)" / "VWAP(DRY_FALLBACK)" / "PROXY" / null.
     * 由 {@code SignalShadowRecorder} 在 record 时透传 {@code SignalRiskDecision.priceImpactBpsSource}.
     * 用于 ShadowVsLive 周报按 source 分桶, 量化 VWAP estimator 与既有 PROXY 算法的胜率差异.
     */
    private String entryEstimatorSource;

    // ── 出场 / 结算 (settlement backfill 写入) ──
    private BigDecimal shadowExitPrice;
    private BigDecimal shadowExitProceedsUsdc;
    private BigDecimal shadowPnlUsdc;
    private Date shadowSettledAt;

    /** PENDING_SETTLEMENT / SETTLED / EXPIRED / NO_DEPTH / DRY_FALLBACK. */
    private String status;

    private Date createdAt;
    private Date updatedAt;
}
