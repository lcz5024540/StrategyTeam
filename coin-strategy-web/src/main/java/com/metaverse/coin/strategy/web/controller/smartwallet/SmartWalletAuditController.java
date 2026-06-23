package com.metaverse.coin.strategy.web.controller.smartwallet;

import com.metaverse.coin.strategy.web.model.smartwallet.AuditAlertView;
import com.metaverse.coin.strategy.web.model.smartwallet.CopyTradingHealthView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderDriftAggregationView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderDriftView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingAggregationHistoryView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingAggregationView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderTraceListView;
import com.metaverse.coin.strategy.web.model.smartwallet.LiveReplayDiffHistoryView;
import com.metaverse.coin.strategy.web.model.smartwallet.MarketLiquidityPercentileView;
import com.metaverse.coin.strategy.web.model.smartwallet.ResolutionAuditView;
import com.metaverse.coin.strategy.web.model.smartwallet.ResolutionTriDiffView;
import com.metaverse.coin.strategy.web.model.smartwallet.ShadowEstimatorSourceComparisonView;
import com.metaverse.coin.strategy.web.model.smartwallet.SettlementSourceAuditView;
import com.metaverse.coin.strategy.web.model.smartwallet.SignalShadowRecordView;
import com.metaverse.coin.strategy.web.model.smartwallet.SlippagePostFactReportView;
import com.metaverse.coin.strategy.web.model.smartwallet.TraceTimelineWebView;
import com.metaverse.coin.strategy.web.service.smartwallet.CopyTradingHealthReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.LeaderRollingAggregationHistoryReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.LeaderRollingAggregationReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.LeaderTraceListReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.LiveReplayDiffHistoryReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.MarketLiquidityPercentileReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.ResolutionAuditReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.ShadowEstimatorSourceComparisonReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletAggregateAuditReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletAuditReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletAuditReportReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.SmartWalletLeaderInsightReadService;
import com.metaverse.coin.strategy.web.service.smartwallet.TraceTimelineReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * Web BFF 端 跟单策略 W1-T3/T4 + 审计可视化入口.
 *
 * 与 SmartWalletReadProxyController 头部注释呼应: 所有 GET 已下沉至 web 模块本地实现,
 * 不再透传 job. 此 controller 是 W1+W2 闭环数据的统一展示入口.
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Slf4j
@RestController
@RequestMapping("/smart-wallet")
@RequiredArgsConstructor
@Tag(name = "Smart Wallet Audit",
     description = "W1-T3 全链路 trace / W1-T4 影子记账 / 审计告警 BFF 接口")
public class SmartWalletAuditController {

    private final TraceTimelineReadService traceTimelineReadService;
    private final SmartWalletAuditReadService auditReadService;
    private final SmartWalletAuditReportReadService auditReportReadService;
    private final SmartWalletLeaderInsightReadService leaderInsightReadService;
    private final MarketLiquidityPercentileReadService marketLiquidityReadService;
    private final LiveReplayDiffHistoryReadService liveReplayDiffHistoryReadService;
    /** 第十一批 第四波 web 接口: pm_resolution_audit 表查询。 */
    private final ResolutionAuditReadService resolutionAuditReadService;
    /** 第十二批 第五波: 聚合审计 (三方 diff + LeaderDrift aggregation). */
    private final SmartWalletAggregateAuditReadService aggregateAuditReadService;
    /** 第十三批 第六波: 按 leader 反查全部 trace_ids. */
    private final LeaderTraceListReadService leaderTraceListReadService;
    /** 第十五批 #1: Shadow estimator source 分桶对比 (VWAP vs PROXY). */
    private final ShadowEstimatorSourceComparisonReadService shadowEstimatorSourceComparisonReadService;
    /** 第十六批 #1: W2-T2 Leader 滑动窗口胜率灰度观察. */
    private final LeaderRollingAggregationReadService leaderRollingAggregationReadService;
    // 第十八批 W2-T2 灰度自动化: trend 历史 (Job 写入的 daily 表)
    private final LeaderRollingAggregationHistoryReadService leaderRollingAggregationHistoryReadService;
    /** 第十九批 #5: 统一 copy-trading-health 端点 (聚合 LeaderRolling / LeaderDrift / LiveReplayDiff). */
    private final CopyTradingHealthReadService copyTradingHealthReadService;

    // ── W1-T3 trace 反查 ─────────────────────────────────────────

    @Operation(summary = "按 trace_id 反查全链路",
               description = "trade_event → follow_task → order → trade_order → position 全链路时间线, 含 W1-T4 影子记账记录.")
    @GetMapping("/trace/{traceId}")
    public TraceTimelineWebView trace(@PathVariable("traceId") String traceId) {
        return traceTimelineReadService.queryByTraceId(traceId);
    }

    // ── W1-T4 影子记账 ────────────────────────────────────────────

    @Operation(summary = "影子记账记录列表",
               description = "支持按 leader / decisionStatus(REJECTED/PAPER_SHADOW/DOWNGRADED) / rejectLayer / status 过滤. 默认 50 条, 上限 500.")
    @GetMapping("/shadow/records")
    public List<SignalShadowRecordView> listShadowRecords(
            @RequestParam(required = false) String leaderAddress,
            @RequestParam(required = false) String decisionStatus,
            @RequestParam(required = false) String rejectLayer,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit) {
        return auditReadService.listShadowRecords(leaderAddress, decisionStatus, rejectLayer, status, limit);
    }

    @Operation(summary = "影子记账单条详情", description = "按 id 直查")
    @GetMapping("/shadow/records/{id}")
    public SignalShadowRecordView getShadowRecord(@PathVariable("id") Long id) {
        return auditReadService.getShadowRecord(id);
    }

    // ── 审计告警 ──────────────────────────────────────────────────

    @Operation(summary = "审计告警列表",
               description = "strategy_alert 表 strategy_id=SMART_WALLET_AUDIT 的告警. alertType 可选 AUDIT_LIVE_REPLAY_DIFF / AUDIT_SHADOW_VS_LIVE / AUDIT_SETTLEMENT_SOURCE / AUDIT_SLIPPAGE_POSTFACT / AUDIT_LEADER_DRIFT.")
    @GetMapping("/audit/alerts")
    public List<AuditAlertView> listAuditAlerts(
            @RequestParam(required = false) String alertType,
            @RequestParam(required = false, defaultValue = "true") Boolean onlyUnresolved,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date sinceCreatedAt,
            @RequestParam(required = false) Integer limit) {
        return auditReadService.listAuditAlerts(alertType, onlyUnresolved, sinceCreatedAt, limit);
    }

    // ── W1-T2 结算源审计实时报表 ──────────────────────────────────

    @Operation(summary = "结算源审计实时报表",
               description = "即时聚合 market_info + position + claim_retry: lifecycle / parseFailure / resolutionDelayed / position 超期 / claim 重试. anomalous=true 表示任一维度异常 (对应 job 端 AUDIT_SETTLEMENT_SOURCE 告警条件).")
    @GetMapping("/audit/settlement-source")
    public SettlementSourceAuditView settlementSourceReport() {
        return auditReportReadService.buildSettlementSourceReport();
    }

    // ── W2-T4.5 滑点事后统计 ───────────────────────────────────────

    @Operation(summary = "滑点事后统计报表",
               description = "回看 N 天 ENTRY 订单 vs leader 入场价偏离分布, 按 category 分桶输出 mean/median/|P75|/|P95|. 默认 7d / 上限 5000 单.")
    @GetMapping("/audit/slippage-postfact")
    public SlippagePostFactReportView slippagePostFactReport(
            @RequestParam(required = false) Integer lookbackDays,
            @RequestParam(required = false) Integer maxOrders) {
        return auditReportReadService.buildSlippageReport(lookbackDays, maxOrders);
    }

    // ── W2-T2 leader 滑动窗口 ──────────────────────────────────────

    @Operation(summary = "Leader 滑动窗口胜率快照",
               description = "取该 leader 最近 N 笔已关闭仓位计算 win rate / 状态 (ACTIVE/COOLING/COLD), 含最近 closes 详情. 启用 W2-T2 灰度时必备的诊断入口.")
    @GetMapping("/leader-rolling/{wallet}")
    public LeaderRollingView leaderRolling(@PathVariable("wallet") String wallet,
                                            @RequestParam(required = false) Integer windowSize) {
        return leaderInsightReadService.queryLeaderRolling(wallet, windowSize);
    }

    // ── W2-T6 leader 行为漂移 ─────────────────────────────────────

    @Operation(summary = "Leader 行为漂移快照",
               description = "聚合该 leader 过去 N 天 trade event 的 7 维 centroid 与最近一笔的 cosine 距离, 输出 ACTIVE/DRIFTED/DRIFTED_SEVERE 状态. 启用 W2-T6 灰度时必备的诊断入口.")
    @GetMapping("/leader-drift/{wallet}")
    public LeaderDriftView leaderDrift(@PathVariable("wallet") String wallet,
                                        @RequestParam(required = false) Integer lookbackDays) {
        return leaderInsightReadService.queryLeaderDrift(wallet, lookbackDays);
    }

    // ── W2-T1.2 流动性分位 ────────────────────────────────────────

    @Operation(summary = "市场流动性分位最新快照",
               description = "返回最近一日各 category 的 P10/P30/P50/P70 + spreadP70 + sampleCount. asOfDate 不传时取最大日期.")
    @GetMapping("/market-liquidity/percentile")
    public List<MarketLiquidityPercentileView> marketLiquidityLatest(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return marketLiquidityReadService.listLatest(asOfDate);
    }

    @Operation(summary = "单一品类流动性分位历史",
               description = "返回单一 category 的最近 N 天 trend, 按 as_of_date desc, 默认 30 天, 上限 365.")
    @GetMapping("/market-liquidity/percentile/{category}")
    public List<MarketLiquidityPercentileView> marketLiquidityHistory(
            @PathVariable("category") String category,
            @RequestParam(required = false) Integer limitDays) {
        return marketLiquidityReadService.historyForCategory(category, limitDays);
    }

    // ── W1-T1 + 下一轮 #7 Live↔Replay diff 历史 ───────────────────

    @Operation(summary = "Live↔Replay diff 最新一日",
               description = "从 live_replay_diff_history 表取最新一行. 含 pnlDiffExceeded / signalMissExceeded 阈值命中标志.")
    @GetMapping("/audit/live-replay-diff/latest")
    public LiveReplayDiffHistoryView liveReplayDiffLatest() {
        return liveReplayDiffHistoryReadService.latest();
    }

    @Operation(summary = "Live↔Replay diff 历史 trend",
               description = "按 as_of_date desc, 默认 30 天, 上限 365. 用于看板画 PnL diff / signal diff 时间序列.")
    @GetMapping("/audit/live-replay-diff/history")
    public List<LiveReplayDiffHistoryView> liveReplayDiffHistory(
            @RequestParam(required = false) Integer limitDays) {
        return liveReplayDiffHistoryReadService.history(limitDays);
    }

    // ── 第十一批 第四波: W1-T2.2 pm_resolution_audit 表 ────────────

    @Operation(summary = "按 marketId 查询 resolution audit 历史",
               description = "按 observed_at desc 返回该市场的全部审计快照, 含 winningOutcome / manualCheckReason / UMA 状态等. 默认 50 条, 上限 500.")
    @GetMapping("/audit/resolution/{marketId}")
    public List<ResolutionAuditView> resolutionAuditByMarketId(
            @PathVariable("marketId") String marketId,
            @RequestParam(required = false) Integer limit) {
        return resolutionAuditReadService.listByMarketId(marketId, limit);
    }

    @Operation(summary = "需要人工 review 的 resolution audit 列表",
               description = "manualCheckReason 非空的记录, 按 observed_at desc. 可选 sinceObservedAt 过滤. 默认 50 条, 上限 500.")
    @GetMapping("/audit/resolution/needs-review")
    public List<ResolutionAuditView> resolutionNeedsReview(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Date sinceObservedAt) {
        return resolutionAuditReadService.listNeedsReview(limit, sinceObservedAt);
    }

    @Operation(summary = "UMA 不一致的 resolution audit 列表",
               description = "closed=true 但 uma_resolved=false 的可疑记录. 在 Gamma/UMA 三方 diff 上线前用于提前发现卡 settlement 市场.")
    @GetMapping("/audit/resolution/uma-inconsistent")
    public List<ResolutionAuditView> resolutionUmaInconsistent(
            @RequestParam(required = false) Integer limit) {
        return resolutionAuditReadService.listUmaInconsistent(limit);
    }

    // ── 第十二批 第五波: 聚合审计 ──────────────────────────────────

    @Operation(summary = "结算三方 diff 实时报表",
               description = "聚合最近 N 小时 pm_resolution_audit, 输出 OURS_VS_UMA / OURS_VS_GAMMA / UMA_VS_GAMMA / HISTORY_CONFLICT 4 类不一致计数 + top-10 实例. lookbackHours 默认 24, 上限按业务自控.")
    @GetMapping("/audit/resolution-tridiff")
    public ResolutionTriDiffView resolutionTriDiff(
            @RequestParam(required = false) Integer lookbackHours) {
        return aggregateAuditReadService.resolutionTriDiff(lookbackHours);
    }

    @Operation(summary = "LeaderDrift 聚合活跃 leader",
               description = "聚合最近 N 小时活跃 leader (按 trade_event 计数). 真实 cosine 距离评估请用 /leader-drift/{wallet}.")
    @GetMapping("/audit/leader-drift-aggregation")
    public LeaderDriftAggregationView leaderDriftAggregation(
            @RequestParam(required = false) Integer lookbackHours) {
        return aggregateAuditReadService.leaderDriftAggregation(lookbackHours);
    }

    // ── 第十三批 第六波: 按 leader 反查全部 trace_ids ──────────────

    @Operation(summary = "按 leader 反查最近 N 天的 trace_id 列表",
               description = "返回该 leader 最近 N 天的 trade_event 列表 (含 traceId / marketId / processed 等). 拿到 traceId 后调 /trace/{traceId} 看全链路详情. 默认 lookbackDays=7, limit=100, 上限 1000.")
    @GetMapping("/traces/by-leader/{wallet}")
    public LeaderTraceListView tracesByLeader(@PathVariable("wallet") String wallet,
                                                @RequestParam(required = false) Integer lookbackDays,
                                                @RequestParam(required = false) Integer limit) {
        return leaderTraceListReadService.query(wallet, lookbackDays, limit);
    }

    // ── 第十五批 #1: Shadow estimator source 分桶对比 (VWAP vs PROXY) ─────

    @Operation(summary = "Shadow estimator source 分桶对比",
               description = "按 entry_estimator_source 分桶聚合 SETTLED 影子记录, 量化 VWAP (W2-T4 第十三批接入) vs 既有 PROXY 算法的胜率/影子 PnL/平均 |slippageBps| 差异. lookbackDays 默认 7, 上限 90; verdict 字段给出简短结论 (样本不足时为 null).")
    @GetMapping("/audit/shadow-estimator-source")
    public ShadowEstimatorSourceComparisonView shadowEstimatorSourceComparison(
            @RequestParam(required = false) Integer lookbackDays) {
        return shadowEstimatorSourceComparisonReadService.buildComparison(lookbackDays);
    }

    // ── 第十六批 #1: W2-T2 Leader 滑动窗口胜率灰度观察 ──────────────

    @Operation(summary = "W2-T2 Leader 滑动窗口胜率灰度聚合",
               description = "聚合最近 N 天活跃 leader 的滑动窗口胜率, 输出 ACTIVE/COOLING/COLD 分布 + top-15 低胜率 leader. lookbackDays 默认 14, 上限 90. 与 job 端 LeaderRollingPerformanceService 算法对齐 (windowSize=20, minSamples=10, minWinRate=0.40).")
    @GetMapping("/audit/leader-rolling-aggregation")
    public LeaderRollingAggregationView leaderRollingAggregation(
            @RequestParam(required = false) Integer lookbackDays) {
        return leaderRollingAggregationReadService.aggregate(lookbackDays);
    }

    // ── 第十八批 W2-T2 灰度自动化: trend 历史端点 ───────────────────────

    @Operation(summary = "W2-T2 Leader 滑动窗口胜率灰度健康历史 (trend)",
               description = "查询 LeaderRollingMonitorJob 每日 09:30 (Asia/Shanghai) 写入的 leader_rolling_aggregation_daily 表; "
                       + "按 asOfDate desc 排序, 同日多 lookbackDays 各占一行. "
                       + "historyDays 默认 30, 上限 180; lookbackDays 可选 (常用 7/14) 进一步过滤. "
                       + "返回 latestHealthStatus 与 rows[].healthStatus 让 ops 监控连续天数告警是否清空, "
                       + "替代 SOP 中'每日人工跑 /audit/leader-rolling-aggregation 表格记录'.")
    @GetMapping("/audit/leader-rolling-aggregation/history")
    public LeaderRollingAggregationHistoryView leaderRollingAggregationHistory(
            @RequestParam(required = false) Integer historyDays,
            @RequestParam(required = false) Integer lookbackDays) {
        return leaderRollingAggregationHistoryReadService.history(historyDays, lookbackDays);
    }

    // ── 第十九批 #5: 统一 copy-trading-health 端点 ─────────────────────

    @Operation(summary = "Copy-Trading 健康总览 (统一端点)",
               description = "把 W2-T2 LeaderRolling trend / W2-T6 LeaderDrift 实时聚合 / "
                       + "W1-T1 LiveReplayDiff 最新一日 三件套聚合为单一面板视图. "
                       + "overallStatus = OK / WARN (任一子源 WARN 即 WARN). "
                       + "降级容错: 任一子源调用异常 → 该字段 null + errors 中记录, 不阻塞另外两源. "
                       + "替代「每日运维 curl 三个端点」的旧 SOP. "
                       + "historyDays 默认 14 (LeaderRolling 回看, 上限 90); "
                       + "driftLookbackHours 默认 24 (LeaderDrift 实时聚合).")
    @GetMapping("/audit/copy-trading-health")
    public CopyTradingHealthView copyTradingHealth(
            @RequestParam(required = false) Integer historyDays,
            @RequestParam(required = false) Integer driftLookbackHours) {
        return copyTradingHealthReadService.health(historyDays, driftLookbackHours);
    }
}
