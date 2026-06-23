package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletClaimRetryMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletClaimRetryEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * W1-T2 (轻量化第一版) 结算源对齐审计服务.
 *
 * <p>本服务在不引入新表 (pm_resolution_audit) 与新 mapper (MarketResolutionAuditMapper)
 * 的前提下, 直接从已有数据源 (smart_wallet_market_info + smart_wallet_position +
 * smart_wallet_claim_retry) 聚合关键指标:
 * <ul>
 *   <li>{@code marketLifecycle} 分布 (ACTIVE / CLOSED_UNRESOLVED / RESOLVED)</li>
 *   <li>RESOLVED 但 {@code resolvedOutcome} 为空 → 解析失败 (parse failure)</li>
 *   <li>CLOSED_UNRESOLVED 且 metadata 同步距今 > N 小时 → 结算延迟 (resolution delay)</li>
 *   <li>仓位结算延迟: status=PENDING_SETTLEMENT 且超过 N 小时仍未 SETTLED</li>
 *   <li>SmartWalletClaimService claim 重试次数分布 (avg / max / count)</li>
 * </ul></p>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率均无任何直接影响</b> — 纯只读
 * 聚合 + 日志输出, 不写库不下单不调风控. 间接价值: 让运维 / 调度通过日志快速识别哪些市场
 * 卡在 UMA 争议 / 解析失败 / claim 失败, 是 T2.5 异常路由的数据基础.</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md (W1-T2).</p>
 *
 * <p><b>注</b>: 完整版 (T2.1 ResolutionAuditRecord 持久化 / T2.3 Polymarket Gamma vs UMA
 * Oracle vs 我方记录三方 diff) 需要引入 {@code pm_resolution_audit} 表 + 调用 Gamma /
 * UMA API, 属于下一轮工单. 当前版本作为基础闭环, 已涵盖 T2.4 中 80% 的看板指标.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementSourceAuditService {

    private static final String LIFECYCLE_ACTIVE = "ACTIVE";
    private static final String LIFECYCLE_CLOSED_UNRESOLVED = "CLOSED_UNRESOLVED";
    private static final String LIFECYCLE_RESOLVED = "RESOLVED";

    private static final String POSITION_STATUS_PENDING_SETTLEMENT = "PENDING_SETTLEMENT";
    private static final String POSITION_STATUS_RESOLVED_UNCLAIMED = "RESOLVED_UNCLAIMED";

    /** 视为"结算延迟"的阈值 (小时). */
    private static final long RESOLUTION_DELAY_THRESHOLD_HOURS = 24L;

    private final SmartWalletProperties properties;
    private final SmartWalletMarketInfoMapper marketInfoMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletClaimRetryMapper claimRetryMapper;
    /**
     * W1-T1.5 / W1-T2 收口告警通道. 仅在 {@link #runScheduled()} 检测到任意 anomaly 维度时
     * 触发, {@link #buildReport()} 纯只读路径不会调用 sink.
     */
    private final SmartWalletAuditAlertSink auditAlertSink;

    /**
     * 显式构建一份审计快照.
     */
    public SettlementSourceAuditReport buildReport() {
        Instant generatedAt = Instant.now();
        Date now = Date.from(generatedAt);
        Date resolutionDelayCutoff = Date.from(
                generatedAt.minus(Duration.ofHours(RESOLUTION_DELAY_THRESHOLD_HOURS)));

        // 1. market_info: lifecycle 分布 + 解析失败 + 结算延迟
        List<SmartWalletMarketInfoEntity> markets = marketInfoMapper.selectList(null);
        Map<String, Integer> lifecycleCount = new LinkedHashMap<>();
        Map<String, Integer> parseFailureByCategory = new LinkedHashMap<>();
        int parseFailureTotal = 0;
        int resolutionDelayedTotal = 0;
        for (SmartWalletMarketInfoEntity m : markets) {
            String lifecycle = m.getMarketLifecycle();
            if (!StringUtils.hasText(lifecycle)) {
                lifecycle = "NULL";
            } else {
                lifecycle = lifecycle.trim().toUpperCase(Locale.ROOT);
            }
            lifecycleCount.merge(lifecycle, 1, Integer::sum);

            if (LIFECYCLE_RESOLVED.equals(lifecycle) && !StringUtils.hasText(m.getResolvedOutcome())) {
                parseFailureTotal++;
                String category = categoryBucket(m);
                parseFailureByCategory.merge(category, 1, Integer::sum);
            }
            if (LIFECYCLE_CLOSED_UNRESOLVED.equals(lifecycle)) {
                Date sync = m.getLastMetadataSyncAt();
                if (sync != null && sync.before(resolutionDelayCutoff)) {
                    resolutionDelayedTotal++;
                }
                // 即使 sync 为 null (未同步过), marketEndAt 在 cutoff 之前同样视为延迟
                if (sync == null && m.getMarketEndAt() != null
                        && m.getMarketEndAt() < resolutionDelayCutoff.getTime()) {
                    resolutionDelayedTotal++;
                }
            }
        }

        // 2. position: PENDING_SETTLEMENT 超期
        List<SmartWalletPositionEntity> stalePositions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus,
                                POSITION_STATUS_PENDING_SETTLEMENT,
                                POSITION_STATUS_RESOLVED_UNCLAIMED)
                        .le(SmartWalletPositionEntity::getClosedAt, resolutionDelayCutoff));
        int pendingSettlementOverdue = 0;
        int resolvedUnclaimedOverdue = 0;
        for (SmartWalletPositionEntity p : stalePositions) {
            if (POSITION_STATUS_PENDING_SETTLEMENT.equalsIgnoreCase(p.getStatus())) {
                pendingSettlementOverdue++;
            } else if (POSITION_STATUS_RESOLVED_UNCLAIMED.equalsIgnoreCase(p.getStatus())) {
                resolvedUnclaimedOverdue++;
            }
        }

        // 3. claim retry: 失败次数分布
        List<SmartWalletClaimRetryEntity> claimRetries = claimRetryMapper.selectList(null);
        int claimRetryCount = claimRetries.size();
        int claimFailureMax = 0;
        long claimFailureSum = 0;
        int claimRetryWithFailures = 0;
        for (SmartWalletClaimRetryEntity c : claimRetries) {
            Integer failures = c.getFailureCount();
            if (failures == null || failures <= 0) {
                continue;
            }
            claimRetryWithFailures++;
            claimFailureSum += failures;
            if (failures > claimFailureMax) {
                claimFailureMax = failures;
            }
        }
        double claimFailureAvg = claimRetryWithFailures == 0 ? 0.0
                : (double) claimFailureSum / claimRetryWithFailures;

        return new SettlementSourceAuditReport()
                .setGeneratedAt(now)
                .setResolutionDelayThresholdHours(RESOLUTION_DELAY_THRESHOLD_HOURS)
                .setTotalMarkets(markets.size())
                .setLifecycleCount(lifecycleCount)
                .setParseFailureTotal(parseFailureTotal)
                .setParseFailureByCategory(parseFailureByCategory)
                .setResolutionDelayedCount(resolutionDelayedTotal)
                .setPositionPendingSettlementOverdue(pendingSettlementOverdue)
                .setPositionResolvedUnclaimedOverdue(resolvedUnclaimedOverdue)
                .setClaimRetryCount(claimRetryCount)
                .setClaimRetryWithFailures(claimRetryWithFailures)
                .setClaimFailureMax(claimFailureMax)
                .setClaimFailureAvg(claimFailureAvg);
    }

    /** Job 调度的实际工作: 跑一次审计, 输出日志, 任意维度超阈值时 WARN. */
    public SettlementSourceAuditReport runScheduled() {
        SettlementSourceAuditReport report = buildReport();
        boolean anyWarn = report.getParseFailureTotal() > 0
                || report.getResolutionDelayedCount() > 0
                || report.getPositionPendingSettlementOverdue() > 0
                || report.getClaimFailureMax() >= 5;
        String banner = anyWarn
                ? "[SettlementSourceAudit][WARN] anomalies detected"
                : "[SettlementSourceAudit] normal";
        log.info("{} generatedAt={} totalMarkets={} lifecycleCount={} parseFailureTotal={} parseFailureByCategory={} resolutionDelayedCount={} pendingSettlementOverdue={} resolvedUnclaimedOverdue={} claimRetryCount={} claimRetryWithFailures={} claimFailureMax={} claimFailureAvg={} resolutionDelayThresholdHours={}",
                banner,
                report.getGeneratedAt(), report.getTotalMarkets(),
                report.getLifecycleCount(),
                report.getParseFailureTotal(), report.getParseFailureByCategory(),
                report.getResolutionDelayedCount(),
                report.getPositionPendingSettlementOverdue(),
                report.getPositionResolvedUnclaimedOverdue(),
                report.getClaimRetryCount(), report.getClaimRetryWithFailures(),
                report.getClaimFailureMax(),
                String.format("%.2f", report.getClaimFailureAvg()),
                report.getResolutionDelayThresholdHours());

        // W1-T1.5: anomaly 收口到统一审计告警 sink. sink 内部已 swallow exception, 这里再防御一层.
        if (anyWarn && auditAlertSink != null) {
            try {
                String title = "结算源审计异常: parseFailure="
                        + report.getParseFailureTotal()
                        + " delayed=" + report.getResolutionDelayedCount()
                        + " pendingSettlementOverdue=" + report.getPositionPendingSettlementOverdue()
                        + " claimFailureMax=" + report.getClaimFailureMax();
                String message = String.format(
                        "lifecycle=%s parseFailureByCategory=%s resolvedUnclaimedOverdue=%d claimRetry={count=%d, withFailures=%d, max=%d, avg=%.2f}",
                        report.getLifecycleCount(),
                        report.getParseFailureByCategory(),
                        report.getPositionResolvedUnclaimedOverdue(),
                        report.getClaimRetryCount(),
                        report.getClaimRetryWithFailures(),
                        report.getClaimFailureMax(),
                        report.getClaimFailureAvg());
                auditAlertSink.publish(AlertType.AUDIT_SETTLEMENT_SOURCE, title, message, null, null);
            } catch (RuntimeException sinkErr) {
                log.warn("[SettlementSourceAudit] auditAlertSink failed: {}", sinkErr.toString(), sinkErr);
            }
        }
        return report;
    }

    private String categoryBucket(SmartWalletMarketInfoEntity m) {
        String c = m.getNormalizedCategory();
        if (StringUtils.hasText(c)) {
            return c.trim().toUpperCase(Locale.ROOT);
        }
        c = m.getSourceCategory();
        if (StringUtils.hasText(c)) {
            return c.trim().toUpperCase(Locale.ROOT);
        }
        return "OTHER";
    }

    /** 结算源对齐审计报表 (W1-T2 轻量化第一版). */
    @Data
    @Accessors(chain = true)
    public static class SettlementSourceAuditReport {
        private Date generatedAt;
        private long resolutionDelayThresholdHours;

        // ── market_info ──
        private int totalMarkets;
        /** marketLifecycle → 计数. */
        private Map<String, Integer> lifecycleCount;
        /** RESOLVED 但 resolvedOutcome 为空 → 解析失败. */
        private int parseFailureTotal;
        /** 解析失败按品类分桶. */
        private Map<String, Integer> parseFailureByCategory;
        /** CLOSED_UNRESOLVED 且 metadata 同步距今 > N 小时 → 结算延迟. */
        private int resolutionDelayedCount;

        // ── position ──
        /** status=PENDING_SETTLEMENT 且 closedAt 距今 > N 小时. */
        private int positionPendingSettlementOverdue;
        /** status=RESOLVED_UNCLAIMED 且 closedAt 距今 > N 小时. */
        private int positionResolvedUnclaimedOverdue;

        // ── claim retry ──
        private int claimRetryCount;
        private int claimRetryWithFailures;
        private int claimFailureMax;
        private double claimFailureAvg;
    }

    /** 留给 Comparator 排序等使用. */
    @SuppressWarnings("unused")
    private static final Comparator<Map.Entry<String, Integer>> ENTRY_BY_VALUE_DESC =
            Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed();
}
