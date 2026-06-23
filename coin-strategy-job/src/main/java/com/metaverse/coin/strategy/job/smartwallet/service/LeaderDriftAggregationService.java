package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import com.metaverse.coin.strategy.job.smartwallet.service.LeaderBehaviorDriftService.LeaderDriftMetrics;
import com.metaverse.coin.strategy.job.smartwallet.service.LeaderBehaviorDriftService.LeaderDriftState;
import com.metaverse.coin.strategy.job.smartwallet.service.LeaderBehaviorDriftService.LeaderDriftStatus;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * W2-T6 / 第八批: Leader 行为漂移日级聚合服务。
 *
 * <p><b>背景</b>: 决议第七批已经说明 {@link LeaderBehaviorDriftService} 在每个信号 hot path
 * 同步评估漂移, 适合做下单决策但不适合直接 fire 告警 (单 leader 单次漂移会污染
 * {@code strategy_alert} 表, 即便 StrategyAlertService 自带去重也无法按日/leader 维度归一化)。
 * 本服务把判定从 hot path 解耦到日级聚合 Job:
 * <ol>
 *   <li>取窗口内 (默认最近 24h) 出现过 trade_event 的所有 leader, 取每个 leader 最新一笔 event;</li>
 *   <li>对每个 leader 调用 {@link LeaderBehaviorDriftService#evaluateForReporting} (绕过 gate 开关);
 *       这样即便 {@code enableLeaderDriftGate=false} 也能积累漂移分布;</li>
 *   <li>按 ACTIVE / DRIFTED / DRIFTED_SEVERE / cold-start 分桶聚合, top-K 漂移 leader 排序;</li>
 *   <li>当
 *       <code>(DRIFTED + DRIFTED_SEVERE) / evaluated ≥ warnDriftedRatio</code>
 *       或
 *       <code>DRIFTED_SEVERE ≥ warnSevereCount</code>
 *       时单条 publish {@link AlertType#AUDIT_LEADER_DRIFT};</li>
 *   <li>否则只打 INFO 日志, 作为后续 W1-T4 影子记账周报横向对比的数据基础。</li>
 * </ol>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: 全部 ⚪ 无直接影响 —
 * 纯只读聚合 + 调用既有 {@link LeaderBehaviorDriftService}#evaluateForReporting (该路径
 * 也是只读 + cache, 不写库) + 日志 + 告警 sink, 完全不触碰 signal/order/position 表, 不参与
 * 任何下单决策路径。间接价值: 让 ops 用一条统一告警观察 "近期是否大量 leader 行为发生漂移",
 * 作为是否启用 {@code enableLeaderDriftGate=true} 灰度的量化依据 (与 W2-T2 同源逻辑)。</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十节末
 * "下一轮" 第 5 项 + 第八批 自动化追加。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderDriftAggregationService {

    private static final String LOG_PREFIX = "[LeaderDriftAggregation]";

    /** 单次查询 trade_event 的硬上限, 防止极端大表场景下 OOM. */
    private static final int TRADE_EVENT_HARD_CAP = 20_000;

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final LeaderBehaviorDriftService leaderBehaviorDriftService;
    /** W1-T1.5 收口告警通道; sink 内部已 swallow exception, 这里再加一层 try/catch 防御. */
    private final SmartWalletAuditAlertSink auditAlertSink;

    /** Job 入口, 调度异常会被 {@link LeaderDriftAggregationJob} 类捕获. */
    public LeaderDriftAggregationReport runScheduled() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null) {
            log.warn("{} audit properties missing, skip", LOG_PREFIX);
            return null;
        }
        LeaderDriftAggregationReport report = buildReport(
                audit.getLeaderDriftAggregationLookbackHours(),
                audit.getLeaderDriftAggregationMaxLeaders());
        logReport(report, audit);
        return report;
    }

    /**
     * 公共方法: 主动构建一份漂移聚合报表 (供运维 / 调试 / 未来 web 接口直调).
     *
     * @param lookbackHours 活跃 leader 回看窗口 (小时), 默认 24
     * @param maxLeaders    评估 leader 数上限, 防止大集合扫描爆炸, 默认 500
     */
    public LeaderDriftAggregationReport buildReport(Integer lookbackHours, Integer maxLeaders) {
        int hours = lookbackHours != null && lookbackHours > 0 ? lookbackHours : 24;
        int leaderCap = maxLeaders != null && maxLeaders > 0 ? maxLeaders : 500;
        Date now = new Date();
        Date since = new Date(now.getTime() - hours * 3_600_000L);

        // 拉窗口内所有 trade_event (limit 硬上限以防 OOM), 按时间倒序;
        // 每个 leader 只保留最新一笔 (Map.putIfAbsent).
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .ge(SmartWalletTradeEventEntity::getCreatedAt, since)
                        .orderByDesc(SmartWalletTradeEventEntity::getCreatedAt)
                        .last("limit " + TRADE_EVENT_HARD_CAP));

        Map<String, SmartWalletTradeEventEntity> lastEventByLeader = new HashMap<>();
        boolean truncated = false;
        for (SmartWalletTradeEventEntity e : events) {
            if (e == null || e.getWalletAddress() == null) continue;
            // 已收满 → 仅记 truncated, 不再扩
            if (lastEventByLeader.size() >= leaderCap
                    && !lastEventByLeader.containsKey(e.getWalletAddress())) {
                truncated = true;
                continue;
            }
            lastEventByLeader.putIfAbsent(e.getWalletAddress(), e);
        }

        int activeLeaders = lastEventByLeader.size();
        int evaluated = 0;
        int activeOk = 0;
        int drifted = 0;
        int driftedSevere = 0;
        int coldStart = 0;
        int skipped = 0;
        List<DriftedLeader> topDrifted = new ArrayList<>();

        for (Map.Entry<String, SmartWalletTradeEventEntity> entry : lastEventByLeader.entrySet()) {
            String leader = entry.getKey();
            LeaderDriftState state;
            try {
                // 关键: 使用 evaluateForReporting 而非 evaluate, 这样即便 enableLeaderDriftGate=false
                // (默认值) 也能跑算法 — 聚合 Job 的核心价值是"在启用门控前就先看到漂移分布".
                state = leaderBehaviorDriftService.evaluateForReporting(leader, entry.getValue());
            } catch (RuntimeException ex) {
                skipped++;
                log.warn("{} evaluate failed leader={} reason={}", LOG_PREFIX, leader, ex.toString());
                continue;
            }
            if (state == null || !state.isGateEnabled()) {
                // SignalRisk 整体未配置 (极少见) → 跳过
                skipped++;
                continue;
            }
            evaluated++;
            LeaderDriftStatus s = state.getStatus();
            if (s == null) {
                skipped++;
                continue;
            }
            LeaderDriftMetrics metrics = state.getMetrics();
            switch (s) {
                case DRIFTED_SEVERE -> {
                    driftedSevere++;
                    topDrifted.add(DriftedLeader.builder()
                            .leaderAddress(leader)
                            .status(s.name())
                            .cosineDistance(metrics == null ? null : metrics.cosineDistance())
                            .sampleCount(metrics == null ? 0 : metrics.sampleCount())
                            .latestEventAt(entry.getValue().getCreatedAt())
                            .reason(state.getReason())
                            .build());
                }
                case DRIFTED -> {
                    drifted++;
                    topDrifted.add(DriftedLeader.builder()
                            .leaderAddress(leader)
                            .status(s.name())
                            .cosineDistance(metrics == null ? null : metrics.cosineDistance())
                            .sampleCount(metrics == null ? 0 : metrics.sampleCount())
                            .latestEventAt(entry.getValue().getCreatedAt())
                            .reason(state.getReason())
                            .build());
                }
                case ACTIVE -> {
                    // metrics 为 null 表示 cold-start; 否则才算真正 ACTIVE
                    if (metrics == null) {
                        coldStart++;
                    } else {
                        activeOk++;
                    }
                }
                default -> skipped++;
            }
        }

        // top-K 按 (SEVERE 优先) + cosineDistance 倒序
        topDrifted.sort(Comparator
                .comparingInt((DriftedLeader d) -> "DRIFTED_SEVERE".equals(d.getStatus()) ? 0 : 1)
                .thenComparing(DriftedLeader::getCosineDistance,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        int topK = 10;
        if (topDrifted.size() > topK) {
            topDrifted = new ArrayList<>(topDrifted.subList(0, topK));
        }

        return LeaderDriftAggregationReport.builder()
                .generatedAt(now)
                .lookbackHours(hours)
                .maxLeaders(leaderCap)
                .activeLeaders(activeLeaders)
                .evaluated(evaluated)
                .activeOkCount(activeOk)
                .driftedCount(drifted)
                .driftedSevereCount(driftedSevere)
                .coldStartCount(coldStart)
                .skippedCount(skipped)
                .truncated(truncated)
                .topDrifted(topDrifted)
                .build();
    }

    private void logReport(LeaderDriftAggregationReport r, SmartWalletProperties.Audit audit) {
        BigDecimal warnRatio = audit.getLeaderDriftAggregationWarnDriftedRatio() == null
                ? new BigDecimal("0.15")
                : audit.getLeaderDriftAggregationWarnDriftedRatio();
        int warnSevereCount = audit.getLeaderDriftAggregationWarnSevereCount() == null
                ? 3
                : audit.getLeaderDriftAggregationWarnSevereCount();

        int totalDrifted = r.getDriftedCount() + r.getDriftedSevereCount();
        BigDecimal driftedRatio = r.getEvaluated() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalDrifted)
                        .divide(BigDecimal.valueOf(r.getEvaluated()), 4, RoundingMode.HALF_UP);
        boolean ratioExceeded = r.getEvaluated() > 0 && driftedRatio.compareTo(warnRatio) >= 0;
        boolean severeExceeded = r.getDriftedSevereCount() >= warnSevereCount;
        boolean anyWarn = ratioExceeded || severeExceeded;

        String banner = anyWarn
                ? "[LeaderDriftAggregation][WARN] drift threshold exceeded"
                : "[LeaderDriftAggregation] within threshold";
        log.info("{} generatedAt={} lookbackHours={} maxLeaders={} activeLeaders={} evaluated={} active={} drifted={} driftedSevere={} coldStart={} skipped={} truncated={} driftedRatio={} warnRatio={} warnSevereCount={}",
                banner,
                r.getGeneratedAt(), r.getLookbackHours(), r.getMaxLeaders(),
                r.getActiveLeaders(), r.getEvaluated(),
                r.getActiveOkCount(), r.getDriftedCount(), r.getDriftedSevereCount(),
                r.getColdStartCount(), r.getSkippedCount(), r.isTruncated(),
                driftedRatio, warnRatio, warnSevereCount);

        // top-3 dump (含 SEVERE 优先排序结果)
        int dump = Math.min(3, r.getTopDrifted().size());
        for (int i = 0; i < dump; i++) {
            DriftedLeader d = r.getTopDrifted().get(i);
            log.info("{}   top#{} leader={} status={} cosineDistance={} samples={} latestEventAt={}",
                    LOG_PREFIX, i + 1, d.getLeaderAddress(), d.getStatus(),
                    d.getCosineDistance(), d.getSampleCount(), d.getLatestEventAt());
        }

        if (!anyWarn) {
            return;
        }
        boolean alertSinkEnabled = audit.isAlertSinkEnabled() && audit.isLeaderDriftAlertEnabled();
        if (!alertSinkEnabled || auditAlertSink == null) {
            return;
        }
        try {
            String title = String.format(Locale.ROOT,
                    "Leader 行为漂移聚合告警: drifted=%d+%d/%d ratio=%s severe=%d",
                    r.getDriftedCount(), r.getDriftedSevereCount(),
                    r.getEvaluated(), driftedRatio, r.getDriftedSevereCount());
            StringBuilder topDump = new StringBuilder();
            int dumpLimit = Math.min(5, r.getTopDrifted().size());
            for (int i = 0; i < dumpLimit; i++) {
                DriftedLeader d = r.getTopDrifted().get(i);
                if (topDump.length() > 0) topDump.append(",");
                topDump.append(d.getStatus())
                        .append(":")
                        .append(shortAddress(d.getLeaderAddress()))
                        .append("(d=").append(d.getCosineDistance())
                        .append(",n=").append(d.getSampleCount())
                        .append(")");
            }
            String message = String.format(Locale.ROOT,
                    "activeLeaders=%d evaluated=%d active=%d drifted=%d driftedSevere=%d coldStart=%d skipped=%d truncated=%s warnRatio=%s warnSevereCount=%d lookbackHours=%d top=%s",
                    r.getActiveLeaders(), r.getEvaluated(),
                    r.getActiveOkCount(), r.getDriftedCount(),
                    r.getDriftedSevereCount(), r.getColdStartCount(),
                    r.getSkippedCount(), r.isTruncated(),
                    warnRatio, warnSevereCount, r.getLookbackHours(),
                    topDump);
            auditAlertSink.publish(AlertType.AUDIT_LEADER_DRIFT, title, message,
                    driftedRatio, warnRatio);
        } catch (RuntimeException ex) {
            log.warn("{} auditAlertSink failed: {}", LOG_PREFIX, ex.toString(), ex);
        }
    }

    private static String shortAddress(String address) {
        if (address == null) return "null";
        if (address.length() <= 10) return address;
        return address.substring(0, 6) + ".." + address.substring(address.length() - 4);
    }

    @Data
    @Builder
    public static class LeaderDriftAggregationReport {
        private Date generatedAt;
        private int lookbackHours;
        private int maxLeaders;
        /** 窗口内有 trade_event 的 distinct leader 数. */
        private int activeLeaders;
        /** 真正调用 evaluateForReporting 且拿到 gateEnabled=true 状态的数量. */
        private int evaluated;
        /** state=ACTIVE 且 metrics != null (即真正过了 cold-start). */
        private int activeOkCount;
        private int driftedCount;
        private int driftedSevereCount;
        /** samples < minSamples 的 leader 数 (放行). */
        private int coldStartCount;
        /** 评估异常 / 缺数据 / 服务返回 disabled() 等. */
        private int skippedCount;
        /** trade_event 超出 hard cap 时 true. */
        private boolean truncated;
        private List<DriftedLeader> topDrifted;
    }

    @Data
    @Builder
    public static class DriftedLeader {
        private String leaderAddress;
        private String status;
        private BigDecimal cosineDistance;
        private int sampleCount;
        private Date latestEventAt;
        private String reason;
    }
}
