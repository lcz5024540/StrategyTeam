package com.metaverse.coin.strategy.job.smartwallet.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * 信号健康诊断视图
 *
 * 用于诊断"为什么策略在过去 N 小时没有开仓"：
 *   - 资金状态（可用资金、持仓成本）
 *   - 钱包池状态（有效 watchlist 数量）
 *   - 信号漏斗（事件数 → 任务数 → 开仓数）
 *   - 拒绝原因分布（SKIPPED 任务的错误分类汇总）
 *   - 共识状态（当前共识窗口内活跃信号数）
 *
 * 由 GET /smart-wallet/diagnostics/signal-health 端点返回。
 */
@Data
@Builder
public class SignalHealthSummaryView {

    // ── 查询范围 ──────────────────────────────────────────────────────────
    /** 回溯时间窗口（小时） */
    private int lookbackHours;
    /** 数据采集时间 */
    private Date capturedAt;

    // ── 资金状态 ──────────────────────────────────────────────────────────
    /** 当前可用资金（USDC） */
    private BigDecimal availableCapitalUsdc;
    /** 配置的最大持仓成本上限（USDC） */
    private BigDecimal maxTotalOpenCostUsdc;
    /** 当前已使用持仓成本（USDC） */
    private BigDecimal openCostUsdc;
    /** 历史已实现盈亏（USDC，负数表示有亏损，会减少可用资金） */
    private BigDecimal realizedPnlUsdc;

    // ── 持仓状态 ──────────────────────────────────────────────────────────
    /** 当前 OPEN 持仓数量 */
    private int openPositionCount;
    /** 最大允许同时持仓数 */
    private int maxOpenPositions;

    // ── 钱包池状态 ────────────────────────────────────────────────────────
    /** watch_enabled=true 且 qualified=true 的有效钱包数 */
    private int qualifiedWatchlistCount;

    // ── 信号漏斗（lookbackHours 窗口内） ──────────────────────────────────
    /** 检测到的链上交易事件数（成为信号来源的原始事件） */
    private long recentTradeEventCount;
    /** 各状态任务数分布 (PENDING/FOLLOWED/SKIPPED/FAILED/EXPIRED 等 → count) */
    private Map<String, Long> tasksByStatus;
    /** 窗口内成功开仓任务数（status=FOLLOWED） */
    private long followedTaskCount;
    /** 窗口内被跳过任务数（status=SKIPPED） */
    private long skippedTaskCount;

    // ── 拒绝原因分析（从 SKIPPED 任务的 errorMessage 聚合，按次数降序） ───
    /**
     * 拒绝原因分类 → 出现次数（一个任务可有多个拒绝原因）
     * 常见分类：
     *   CONSENSUS_WALLETS   - 共识钱包数不足（最常见原因）
     *   CONSENSUS_SCORE     - 共识评分不足
     *   SIGNAL_FRESHNESS    - 信号过期
     *   CAPITAL_LIMIT       - 持仓成本超上限
     *   CAPITAL_DEPLETED    - 可用资金耗尽（availableCapital ≤ 0）
     *   POSITION_LIMIT      - 持仓数量超上限
     *   DUPLICATE_POSITION  - 市场已有相同方向持仓
     *   MARKET_TIMING       - 市场结束时间不符（max-hours-to-market-end 过小）
     *   TRIGGER_SIZE        - 触发笔金额太小
     *   PRICE_RANGE         - 价格超出跟单区间
     *   WATCHLIST           - 钱包未在 watchlist
     *   CONCENTRATION       - 市场集中度过高（控盘风险）
     *   EXPIRED             - 任务因超时被自动清理
     */
    private Map<String, Long> topRejectionCategories;

    // ── 共识状态 ──────────────────────────────────────────────────────────
    /** 当前共识窗口内活跃共识信号数 */
    private int consensusSignalCount;
    /** 共识窗口大小（秒） */
    private int consensusWindowSeconds;
    /** 共识门控是否开启 */
    private boolean consensusGateEnabled;
    /** 当前配置的最小实体数要求 */
    private int minDistinctEntities;

    // ── 健康判断 ──────────────────────────────────────────────────────────
    /** 是否存在明显的结构性阻断（可用资金 ≤ 0 / watchlist 为空） */
    private boolean criticalBlockDetected;
    /** 主要阻断原因描述（供运营快速定位） */
    private String primaryBlockReason;
}
