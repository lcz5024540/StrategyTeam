package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PmResolutionAuditMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmResolutionAuditEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 第十一批: Gamma / UMA / 我方 结算三方 diff 审计.
 *
 * <p><b>核心问题</b>: 一个市场是否被识别为 RESOLVED, 我们有三个独立 source —
 * <ol>
 *   <li>我方 {@link MarketResolutionResolver} 给出的 {@code resolutionStatus + winningOutcome}</li>
 *   <li>Polymarket Gamma 给的 {@code closed} 标记 (+ acceptingOrders 推断)</li>
 *   <li>UMA Oracle 的 {@code umaResolved} 标记</li>
 * </ol>
 * 三者理论上应一致, 实际可能出现:
 * <ul>
 *   <li><b>OURS_VS_UMA</b>: 我方 RESOLVED 但 UMA 未 resolved — 可能是预解析过早</li>
 *   <li><b>OURS_VS_GAMMA</b>: 我方 UNRESOLVED 但 Gamma closed — 解析逻辑漏判</li>
 *   <li><b>UMA_VS_GAMMA</b>: Gamma closed 但 UMA 未 resolved — 平台层不一致 (罕见)</li>
 *   <li><b>HISTORY_CONFLICT</b>: 同一 marketId 在不同时刻给出不同 winningOutcome — 我方解析漂移</li>
 * </ul>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> — 纯只读
 * 聚合 + 日志 + 告警 sink, 不修改任何业务表; 失败仅 swallow.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十八节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionTriDiffService {

    private static final String LOG_PREFIX = "[ResolutionTriDiff]";

    private final SmartWalletProperties properties;
    private final PmResolutionAuditMapper auditMapper;
    private final SmartWalletAuditAlertSink auditAlertSink;
    /**
     * 第十二批 #4: 可选 live refresh 服务. 注入失败 / 配置关闭都不影响 diff 主流程.
     */
    @Autowired(required = false)
    private ResolutionLiveRefreshService resolutionLiveRefreshService;

    /** Job 入口. 调度异常由 Job 类捕获. */
    public ResolutionTriDiffReport runScheduled() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        int lookback = audit == null || audit.getResolutionTriDiffLookbackHours() == null
                ? 24 : audit.getResolutionTriDiffLookbackHours();
        ResolutionTriDiffReport report = buildReport(lookback);
        logReportAndAlert(report, audit);
        // 第十二批 #4: 可选刷新 top conflicted markets 的 live 状态, 写新 audit 记录
        // 让下一轮扫描能看到"我方观测过时"的修正. 失败仅 warn.
        if (resolutionLiveRefreshService != null
                && audit != null
                && audit.isResolutionTriDiffLiveRefreshEnabled()
                && report.getConflictedMarketIds() != null
                && !report.getConflictedMarketIds().isEmpty()) {
            try {
                resolutionLiveRefreshService.refresh(report.getConflictedMarketIds());
            } catch (RuntimeException ex) {
                log.warn("{} live refresh failed: {}", LOG_PREFIX, ex.toString());
            }
        }
        return report;
    }

    /**
     * 直调入口 (供运维 / 调试 / 未来 web 接口).
     *
     * @param lookbackHours 回看窗口 (小时), null/<=0 时 fallback 24h
     */
    public ResolutionTriDiffReport buildReport(Integer lookbackHours) {
        int hours = lookbackHours != null && lookbackHours > 0 ? lookbackHours : 24;
        Date now = new Date();
        Date since = new Date(now.getTime() - hours * 3_600_000L);

        List<PmResolutionAuditEntity> rows = auditMapper.selectList(
                new LambdaQueryWrapper<PmResolutionAuditEntity>()
                        .ge(PmResolutionAuditEntity::getObservedAt, since)
                        .orderByDesc(PmResolutionAuditEntity::getObservedAt));

        int total = rows.size();
        int oursVsUma = 0;
        int oursVsGamma = 0;
        int umaVsGamma = 0;
        int umaDisputedCount = 0;

        // 按 marketId 收集所有 winningOutcome, 找历史漂移
        Map<String, List<String>> winningByMarket = new HashMap<>();
        List<TriDiffInstance> instances = new ArrayList<>();

        for (PmResolutionAuditEntity r : rows) {
            boolean weResolved = "RESOLVED".equalsIgnoreCase(r.getResolutionStatus());
            boolean gammaClosed = Boolean.TRUE.equals(r.getClosed());
            boolean umaResolved = Boolean.TRUE.equals(r.getUmaResolved());
            // F-P1-12: 引入 UMA 争议维度
            boolean umaDisputed = Boolean.TRUE.equals(r.getUmaDisputed());

            DiffType type = null;
            // F-P1-12: 优先识别 UMA 争议 — 该样本即便 gammaClosed 也不应判为 OURS_VS_GAMMA
            // (我方未解析是合理的, 等 UMA 争议结束再说)
            if (umaDisputed) {
                type = DiffType.UMA_DISPUTED;
                umaDisputedCount++;
            } else if (weResolved && !umaResolved) {
                type = DiffType.OURS_VS_UMA;
                oursVsUma++;
            } else if (!weResolved && gammaClosed) {
                type = DiffType.OURS_VS_GAMMA;
                oursVsGamma++;
            } else if (gammaClosed && !umaResolved) {
                type = DiffType.UMA_VS_GAMMA;
                umaVsGamma++;
            }
            if (type != null) {
                instances.add(TriDiffInstance.builder()
                        .marketId(r.getMarketId())
                        .observedAt(r.getObservedAt())
                        .diffType(type)
                        .resolutionStatus(r.getResolutionStatus())
                        .winningOutcome(r.getWinningOutcome())
                        .gammaClosed(gammaClosed)
                        .umaResolutionStatus(r.getUmaResolutionStatus())
                        .umaResolved(umaResolved)
                        .umaDisputed(umaDisputed)
                        .umaDisputeObservedAt(r.getUmaDisputeObservedAt())
                        .build());
            }

            if (r.getMarketId() != null && r.getWinningOutcome() != null) {
                winningByMarket.computeIfAbsent(r.getMarketId(), k -> new ArrayList<>())
                        .add(r.getWinningOutcome());
            }
        }

        // 历史漂移 = 同一 market 出现 ≥ 2 个不同 winningOutcome
        int historyConflict = 0;
        List<String> conflictedMarkets = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : winningByMarket.entrySet()) {
            long distinct = e.getValue().stream().filter(Objects::nonNull).distinct().count();
            if (distinct > 1) {
                historyConflict++;
                conflictedMarkets.add(e.getKey());
            }
        }

        int anomalyTotal = oursVsUma + oursVsGamma + umaVsGamma + historyConflict + umaDisputedCount;

        // top-N 不一致的 instance, 按 observedAt desc
        int topN = 10;
        List<TriDiffInstance> topInstances = instances.size() > topN
                ? instances.subList(0, topN)
                : instances;

        return ResolutionTriDiffReport.builder()
                .generatedAt(now)
                .lookbackHours(hours)
                .totalAuditRows(total)
                .anomalyTotal(anomalyTotal)
                .oursVsUmaCount(oursVsUma)
                .oursVsGammaCount(oursVsGamma)
                .umaVsGammaCount(umaVsGamma)
                .historyConflictCount(historyConflict)
                .umaDisputedCount(umaDisputedCount)
                .conflictedMarketIds(conflictedMarkets)
                .topInstances(topInstances)
                .build();
    }

    private void logReportAndAlert(ResolutionTriDiffReport r, SmartWalletProperties.Audit audit) {
        int warnThreshold = audit == null || audit.getResolutionTriDiffWarnThreshold() == null
                ? 5 : audit.getResolutionTriDiffWarnThreshold();
        boolean anomalous = r.getAnomalyTotal() >= warnThreshold;

        if (anomalous) {
            log.warn("{}[WARN] anomalies={} (oursVsUma={} oursVsGamma={} umaVsGamma={} historyConflict={} umaDisputed={}) threshold={} topMarkets={}",
                    LOG_PREFIX, r.getAnomalyTotal(),
                    r.getOursVsUmaCount(), r.getOursVsGammaCount(),
                    r.getUmaVsGammaCount(), r.getHistoryConflictCount(), r.getUmaDisputedCount(),
                    warnThreshold,
                    truncateMarkets(r.getConflictedMarketIds(), 5));
            try {
                String message = String.format(
                        "结算三方 diff: anomalies=%d (oursVsUma=%d oursVsGamma=%d umaVsGamma=%d historyConflict=%d umaDisputed=%d), 阈值=%d. 冲突 marketId: %s",
                        r.getAnomalyTotal(), r.getOursVsUmaCount(), r.getOursVsGammaCount(),
                        r.getUmaVsGammaCount(), r.getHistoryConflictCount(), r.getUmaDisputedCount(),
                        warnThreshold, truncateMarkets(r.getConflictedMarketIds(), 3));
                auditAlertSink.publish(AlertType.AUDIT_RESOLUTION_TRIDIFF,
                        "结算三方 diff 不一致", message,
                        BigDecimal.valueOf(r.getAnomalyTotal()), BigDecimal.valueOf(warnThreshold));
            } catch (RuntimeException ex) {
                log.warn("{} alert sink publish failed: {}", LOG_PREFIX, ex.toString());
            }
        } else {
            log.info("{} total={} anomalies={} (oursVsUma={} oursVsGamma={} umaVsGamma={} historyConflict={} umaDisputed={}) within threshold={}",
                    LOG_PREFIX, r.getTotalAuditRows(), r.getAnomalyTotal(),
                    r.getOursVsUmaCount(), r.getOursVsGammaCount(),
                    r.getUmaVsGammaCount(), r.getHistoryConflictCount(), r.getUmaDisputedCount(), warnThreshold);
        }
    }

    private String truncateMarkets(List<String> markets, int n) {
        if (markets == null || markets.isEmpty()) return "[]";
        int take = Math.min(n, markets.size());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < take; i++) {
            if (i > 0) sb.append(", ");
            sb.append(markets.get(i));
        }
        if (markets.size() > take) sb.append(", ...+").append(markets.size() - take);
        return sb.append("]").toString();
    }

    /** 三方 diff 类型. */
    public enum DiffType {
        OURS_VS_UMA,        // 我方 RESOLVED 但 UMA 未 resolved
        OURS_VS_GAMMA,      // 我方 UNRESOLVED 但 Gamma closed
        UMA_VS_GAMMA,       // Gamma closed 但 UMA 未 resolved
        HISTORY_CONFLICT,   // 同 marketId 历史 winningOutcome 漂移 (用于报表层标记, 不单独 fire)
        UMA_DISPUTED        // F-P1-12: UMA Oracle 争议中 (优先级最高, 即便 gammaClosed 也单独归类)
    }

    @Data
    @Builder
    public static class ResolutionTriDiffReport {
        private Date generatedAt;
        private int lookbackHours;
        private int totalAuditRows;
        private int anomalyTotal;
        private int oursVsUmaCount;
        private int oursVsGammaCount;
        private int umaVsGammaCount;
        private int historyConflictCount;
        /** F-P1-12: UMA 争议样本数 (优先级最高的 unresolved 分类). */
        private int umaDisputedCount;
        private List<String> conflictedMarketIds;
        private List<TriDiffInstance> topInstances;

        /** 给 web 接口 / 调试用的扁平 map 视图. */
        public Map<String, Integer> toCountsMap() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("totalAuditRows", totalAuditRows);
            m.put("anomalyTotal", anomalyTotal);
            m.put("oursVsUma", oursVsUmaCount);
            m.put("oursVsGamma", oursVsGammaCount);
            m.put("umaVsGamma", umaVsGammaCount);
            m.put("historyConflict", historyConflictCount);
            m.put("umaDisputed", umaDisputedCount);
            return m;
        }
    }

    @Data
    @Builder
    public static class TriDiffInstance {
        private String marketId;
        private Date observedAt;
        private DiffType diffType;
        private String resolutionStatus;
        private String winningOutcome;
        private boolean gammaClosed;
        private String umaResolutionStatus;
        private boolean umaResolved;
        /** F-P1-12: UMA 争议中标记. */
        private boolean umaDisputed;
        /** F-P1-12: 首次观察到争议的时间 (来自 entity). */
        private Date umaDisputeObservedAt;
    }
}
