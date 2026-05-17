package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * W1-T2.1 单次市场结算解析的审计快照.
 *
 * <p>每次 {@link MarketResolutionResolver#resolveWithAudit} 调用都会产出一份 record,
 * 由调用方按需落表 / 入审计聚合管道. 当前为内存 POJO, 不直接持久化:
 * <ul>
 *   <li>本 record 与 {@link MarketResolutionResolver.Resolution} 互补 — Resolution 是给
 *       业务用的结论, ResolutionAuditRecord 是给审计/告警用的"过程数据".</li>
 *   <li>留存原始 outcomes / outcomePrices / 是否 closed / acceptingOrders / umaResolutionStatus
 *       这些 input 字段, 便于事后回放每一次解析的输入条件.</li>
 * </ul></p>
 *
 * <p>未来如需持久化, 仅需新增 mapper + DDL (pm_resolution_audit), 字段映射已对齐 — 见
 * docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T2).</p>
 */
@Data
@Builder
@Accessors(chain = true)
public class ResolutionAuditRecord {

    /** 解析时刻 (服务器本地时间). */
    private Date observedAt;

    /** Polymarket conditionId / 市场 ID — 由调用方传入, 解析器内部不持有. */
    private String marketId;

    /** Resolution.resolutionStatus — RESOLVED / WAIT_FINAL_PRICE / NEED_MANUAL_CHECK / UNRESOLVED. */
    private String resolutionStatus;

    /** 数据来源标签: TOKEN_WINNER / WINNING_TOKEN_ID / EXPLICIT_WINNER / FINAL_PRICE_INFERENCE / null. */
    private String resolutionSource;

    /** 解析出的获胜 outcome (大写). */
    private String winningOutcome;

    /** 解析出的获胜 tokenId. */
    private String winningTokenId;

    /** 解析失败 / 需人工 review 时的具体 reason 标签. */
    private String manualCheckReason;

    /** 原始 closed 标记 (Gamma). */
    private boolean closed;

    /** 原始 acceptingOrders 是否存在 (用于区分 missing vs false). */
    private boolean acceptingOrdersPresent;

    /** 原始 acceptingOrders 值. */
    private boolean acceptingOrders;

    /** UMA Oracle 状态原文 (用于审计时校验 disputed_flag). */
    private String umaResolutionStatus;

    /** 是否被识别为 UMA 已解决 (RESOLVED / SETTLED / FINAL 等同义词命中). */
    private boolean umaResolved;

    /**
     * F-P1-12: UMA Oracle 是否处于争议中 (disputed=true)。
     * <p>取自 Polymarket Gamma metadata 的 {@code disputed} / {@code umaDisputed} 字段；
     * 用于 tri-diff 审计区分"我方未解析"与"UMA 争议中"两种不同的 unresolved 状态。
     */
    private boolean umaDisputed;

    /**
     * F-P1-12: 第一次观测到 UMA 争议的时间。当前次刷新若 disputed=true 则置 now()，
     * 否则置 null；持久化层 (PmResolutionAuditEntity#umaDisputeObservedAt) 与之对齐。
     * <p>用于 audit 查询"该 marketId 自何时起进入争议"的快速反查。
     */
    private Date umaDisputeObservedAt;

    /** 原始 outcomes 列表 (副本快照). */
    private List<String> outcomes;

    /** 原始 outcome 价格列表 (副本快照). */
    private List<String> outcomePrices;

    /** outcome → tokenId 映射快照. */
    private Map<String, String> outcomeTokenIds;

    /**
     * 解析结果是否需要人工介入 (status ∈ NEED_MANUAL_CHECK / WAIT_FINAL_PRICE).
     */
    public boolean isNeedsReview() {
        return MarketResolutionResolver.STATUS_NEED_MANUAL_CHECK.equals(resolutionStatus)
                || MarketResolutionResolver.STATUS_WAIT_FINAL_PRICE.equals(resolutionStatus);
    }

    /** 解析结果是否已最终落定. */
    public boolean isResolved() {
        return MarketResolutionResolver.STATUS_RESOLVED.equals(resolutionStatus);
    }
}
