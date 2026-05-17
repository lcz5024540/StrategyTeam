package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.LeaderRollingAggregationDailyMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.LeaderRollingAggregationDailyEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.engine.monitor.AlertType;
import com.metaverse.coin.strategy.job.smartwallet.audit.SmartWalletAuditAlertSink;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * W2-T2 灰度自动化 (第十八批): leader 滑动窗口胜率日级聚合服务.
 *
 * <p><b>背景</b>: 第十七批的 {@code w2-t2-grayscale-sop.md} 把"每日运维人工跑 web 端点 + 在
 * 表格里贴数字"作为灰度→prod 的判断依据. 这种 SOP 在批次少时可用, 长期会被遗忘 / 数据缺失 /
 * trend 无人看. 本服务把 SOP 升级为 **自动 Job + 历史落表 + 异常告警**:
 * <ol>
 *   <li>每日 (默认 09:30 Asia/Shanghai, 紧跟 LeaderDriftAggregation 08:00 之后) 触发;</li>
 *   <li>对每个 lookbackDays (默认仅 7d, 可在 YAML 配置 {@code 7,14} 双档) 跑一次聚合;</li>
 *   <li>算法与 web BFF {@code LeaderRollingAggregationReadService} 对齐 (取每个 leader 最近
 *       windowSize=20 笔已关闭仓位, 按 minSamples / minWinRate 分桶 ACTIVE/COOLING/COLD);</li>
 *   <li>计算 coolingRatio (= coolingCount / evaluatedCount) 与 overallWinRate (加权);</li>
 *   <li>按阈值 (warnCoolingRatio / warnMinOverallWinRate) 判 health: OK /
 *       WARN_HIGH_COOLING / WARN_LOW_WIN_RATE / WARN_BOTH;</li>
 *   <li>upsert 一行到 {@code leader_rolling_aggregation_daily} (主键
 *       (as_of_date, lookback_days) 幂等, 同日重跑只覆盖最新值);</li>
 *   <li>health != OK 时单条 publish {@link AlertType#AUDIT_LEADER_ROLLING_HEALTH}.</li>
 * </ol>
 *
 * <p><b>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响</b>: 全部 ⚪ 无直接影响 —
 * 纯只读聚合 + upsert audit 表 + 日志 + 告警 sink, 不参与 signal/order/position 决策路径.
 * 与 {@link LeaderRollingPerformanceService} (hot path 门控) 完全解耦, 即便本 Job 失败也不
 * 影响实时门控. 间接价值: 让 ops 用一条统一告警观察"近期 leader 池胜率分布是否健康",
 * 替代每日人工 SOP 查询.</p>
 *
 * <p>详见 docs/polymarket/audit-observability/w2-t2-grayscale-sop.md 与
 * docs/polymarket/copy-trading-improvement-decision-2026-05-17.md 第三节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderRollingMonitorService {

    private static final String LOG_PREFIX = "[LeaderRollingMonitor]";
    private static final String ORDER_SIDE_BUY = "BUY";
    private static final String ORDER_TYPE_ENTRY = "ENTRY";
    private static final List<String> SETTLED_STATUSES = List.of("CLOSED", "SETTLED", "CLAIMED");

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_COOLING = "COOLING";
    private static final String STATUS_COLD = "COLD";

    static final String HEALTH_OK = "OK";
    static final String HEALTH_WARN_HIGH_COOLING = "WARN_HIGH_COOLING";
    static final String HEALTH_WARN_LOW_WIN_RATE = "WARN_LOW_WIN_RATE";
    static final String HEALTH_WARN_BOTH = "WARN_BOTH";

    private final SmartWalletProperties properties;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final LeaderRollingAggregationDailyMapper aggregationDailyMapper;
    private final SmartWalletAuditAlertSink auditAlertSink;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    /** Job 入口: 调度异常会被 {@code LeaderRollingMonitorJob} 类捕获. */
    public List<LeaderRollingAggregationSnapshot> runScheduled() {
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null) {
            log.warn("{} audit properties missing, skip", LOG_PREFIX);
            return Collections.emptyList();
        }
        List<Integer> lookbacks = audit.getLeaderRollingAggregationLookbackDays();
        if (lookbacks == null || lookbacks.isEmpty()) {
            lookbacks = Collections.singletonList(7);
        }
        ZoneId zoneId = parseZone(audit.getLeaderRollingAggregationZone());
        LocalDate today = LocalDate.now(zoneId);
        Date now = new Date();

        List<LeaderRollingAggregationSnapshot> results = new ArrayList<>(lookbacks.size());
        for (Integer rawLookback : lookbacks) {
            int days = rawLookback == null || rawLookback <= 0 ? 7 : rawLookback;
            try {
                LeaderRollingAggregationSnapshot snap = computeAndPersist(today, days, audit, now);
                results.add(snap);
                publishAlertIfNeeded(snap, audit);
            } catch (RuntimeException ex) {
                log.error("{} lookback={}d aggregation failed: {}", LOG_PREFIX, days, ex.toString(), ex);
            }
        }
        return results;
    }

    /**
     * 公共方法: 主动构建一份聚合快照 (供运维 / 调试 / 测试直调, 不落库).
     * 算法与 {@link #computeAndPersist} 一致, 但跳过 upsert 与告警.
     */
    public LeaderRollingAggregationSnapshot dryRun(int lookbackDays) {
        SmartWalletProperties.Audit audit = properties.getAudit();
        if (audit == null) {
            throw new IllegalStateException("audit properties missing");
        }
        return compute(LocalDate.now(parseZone(audit.getLeaderRollingAggregationZone())),
                lookbackDays, audit, new Date());
    }

    // ───────────────────────── compute ─────────────────────────

    private LeaderRollingAggregationSnapshot computeAndPersist(
            LocalDate today, int lookbackDays, SmartWalletProperties.Audit audit, Date now) {
        LeaderRollingAggregationSnapshot snap = compute(today, lookbackDays, audit, now);
        upsert(snap);
        log.info("{} as_of_date={} lookbackDays={} activeLeaders={} evaluated={} active={} cooling={} cold={} "
                        + "coolingRatio={} overallWinRate={} health={}",
                LOG_PREFIX, snap.getAsOfDate(), snap.getLookbackDays(),
                snap.getActiveLeaders(), snap.getEvaluatedCount(),
                snap.getActiveCount(), snap.getCoolingCount(), snap.getColdCount(),
                snap.getCoolingRatio(), snap.getOverallWinRate(), snap.getHealthStatus());
        return snap;
    }

    private LeaderRollingAggregationSnapshot compute(
            LocalDate today, int lookbackDays, SmartWalletProperties.Audit audit, Date now) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        int windowSize = defaultInt(risk == null ? null : risk.getLeaderRollingWindowSize(), 20);
        int minSamples = defaultInt(risk == null ? null : risk.getLeaderRollingMinSamples(), 8);
        BigDecimal minWinRate = nonNullOr(risk == null ? null : risk.getLeaderRollingMinWinRate(),
                new BigDecimal("0.45"));
        int minEvaluatedForHealth = defaultInt(audit.getLeaderRollingAggregationMinEvaluatedCount(), 10);

        Date since = new Date(now.getTime() - lookbackDays * 86_400_000L);

        // 1) 拉窗口内的 BUY+ENTRY 订单, 收集每个 leader 的 positionId 列表 (按 filledAt desc)
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getSide, ORDER_SIDE_BUY)
                        .eq(SmartWalletOrderEntity::getOrderType, ORDER_TYPE_ENTRY)
                        .isNotNull(SmartWalletOrderEntity::getPositionId)
                        .isNotNull(SmartWalletOrderEntity::getFilledAt)
                        .ge(SmartWalletOrderEntity::getFilledAt, since)
                        .orderByDesc(SmartWalletOrderEntity::getFilledAt));

        Map<String, List<Long>> walletToPositions = new HashMap<>();
        for (SmartWalletOrderEntity o : orders) {
            if (o.getWalletAddress() == null || o.getPositionId() == null) continue;
            walletToPositions.computeIfAbsent(o.getWalletAddress(), k -> new ArrayList<>())
                    .add(o.getPositionId());
        }
        int activeLeaders = walletToPositions.size();

        // 2) batch 查所有 position 状态
        List<Long> allPositionIds = new ArrayList<>();
        for (List<Long> ids : walletToPositions.values()) {
            allPositionIds.addAll(ids);
        }
        Map<Long, SmartWalletPositionEntity> closedById = new HashMap<>();
        if (!allPositionIds.isEmpty()) {
            positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                            .in(SmartWalletPositionEntity::getId, allPositionIds)
                            .in(SmartWalletPositionEntity::getStatus, SETTLED_STATUSES))
                    .forEach(p -> closedById.put(p.getId(), p));
        }

        // 3) 对每个 leader, 取最近 windowSize 个已关闭 position, 计算 winRate
        int activeCount = 0;
        int coolingCount = 0;
        int coldCount = 0;
        int totalSamples = 0;
        int totalWins = 0;
        List<LeaderRow> leaderRows = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : walletToPositions.entrySet()) {
            String leader = entry.getKey();
            int wins = 0;
            int losses = 0;
            BigDecimal totalPnl = BigDecimal.ZERO;
            int taken = 0;
            for (Long pid : entry.getValue()) {
                if (taken >= windowSize) break;
                SmartWalletPositionEntity p = closedById.get(pid);
                if (p == null) continue;
                BigDecimal pnl = p.getRealizedPnlUsdc() == null ? BigDecimal.ZERO : p.getRealizedPnlUsdc();
                if (pnl.signum() > 0) wins++;
                else losses++;
                totalPnl = totalPnl.add(pnl);
                taken++;
            }
            int samples = wins + losses;
            BigDecimal winRate = samples == 0 ? null
                    : new BigDecimal(wins).divide(new BigDecimal(samples), 4, RoundingMode.HALF_UP);

            String status;
            if (samples < minSamples) {
                status = STATUS_COLD;
                coldCount++;
            } else if (winRate.compareTo(minWinRate) < 0) {
                status = STATUS_COOLING;
                coolingCount++;
            } else {
                status = STATUS_ACTIVE;
                activeCount++;
            }
            totalSamples += samples;
            totalWins += wins;
            leaderRows.add(new LeaderRow(leader, samples, wins, losses, winRate,
                    totalPnl.setScale(4, RoundingMode.HALF_UP), status));
        }

        int evaluated = activeCount + coolingCount;
        BigDecimal coolingRatio = evaluated == 0 ? null
                : new BigDecimal(coolingCount).divide(new BigDecimal(evaluated), 4, RoundingMode.HALF_UP);
        BigDecimal overallWinRate = totalSamples == 0 ? null
                : new BigDecimal(totalWins).divide(new BigDecimal(totalSamples), 4, RoundingMode.HALF_UP);

        // 4) health 判定 (evaluated < minEvaluatedForHealth 时统一 OK, 样本不足不告警)
        String health = HEALTH_OK;
        BigDecimal warnCoolingRatio = nonNullOr(audit.getLeaderRollingAggregationWarnCoolingRatio(),
                new BigDecimal("0.40"));
        BigDecimal warnMinOverallWinRate = nonNullOr(audit.getLeaderRollingAggregationWarnMinOverallWinRate(),
                new BigDecimal("0.48"));
        if (evaluated >= minEvaluatedForHealth) {
            boolean highCooling = coolingRatio != null && coolingRatio.compareTo(warnCoolingRatio) >= 0;
            boolean lowWin = overallWinRate != null && overallWinRate.compareTo(warnMinOverallWinRate) < 0;
            if (highCooling && lowWin) health = HEALTH_WARN_BOTH;
            else if (highCooling) health = HEALTH_WARN_HIGH_COOLING;
            else if (lowWin) health = HEALTH_WARN_LOW_WIN_RATE;
        }

        // 5) top-N 低胜率 leader (cooling 优先) → JSON
        int topN = Math.max(0, defaultInt(audit.getLeaderRollingAggregationTopNToCapture(), 5));
        String topJson = buildTopCoolingJson(leaderRows, topN);

        return LeaderRollingAggregationSnapshot.builder()
                .asOfDate(today)
                .lookbackDays(lookbackDays)
                .windowSize(windowSize)
                .minSamples(minSamples)
                .minWinRate(minWinRate)
                .activeLeaders(activeLeaders)
                .evaluatedCount(evaluated)
                .activeCount(activeCount)
                .coolingCount(coolingCount)
                .coldCount(coldCount)
                .coolingRatio(coolingRatio)
                .overallWinRate(overallWinRate)
                .healthStatus(health)
                .topCoolingLeadersJson(topJson)
                .warnCoolingRatio(warnCoolingRatio)
                .warnMinOverallWinRate(warnMinOverallWinRate)
                .computedAt(now)
                .build();
    }

    private String buildTopCoolingJson(List<LeaderRow> rows, int topN) {
        if (topN <= 0 || rows.isEmpty()) return null;
        List<LeaderRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparing((LeaderRow r) -> {
                    if (STATUS_COOLING.equals(r.status)) return 0;
                    if (STATUS_COLD.equals(r.status)) return 1;
                    return 2;
                })
                .thenComparing(r -> r.winRate, Comparator.nullsLast(Comparator.naturalOrder())));
        List<LeaderRow> top = sorted.subList(0, Math.min(topN, sorted.size()));
        List<Map<String, Object>> out = new ArrayList<>(top.size());
        for (LeaderRow r : top) {
            Map<String, Object> m = new HashMap<>();
            m.put("leader", r.leader);
            m.put("samples", r.samples);
            m.put("wins", r.wins);
            m.put("losses", r.losses);
            m.put("winRate", r.winRate == null ? null : r.winRate.toPlainString());
            m.put("totalPnlUsdc", r.totalPnlUsdc.toPlainString());
            m.put("status", r.status);
            out.add(m);
        }
        try {
            return jsonMapper.writeValueAsString(out);
        } catch (JsonProcessingException ex) {
            log.warn("{} topCoolingLeadersJson serialize failed: {}", LOG_PREFIX, ex.toString());
            return null;
        }
    }

    // ───────────────────────── upsert ─────────────────────────

    private void upsert(LeaderRollingAggregationSnapshot s) {
        Date now = new Date();
        LeaderRollingAggregationDailyEntity existing = aggregationDailyMapper.selectOne(
                new LambdaQueryWrapper<LeaderRollingAggregationDailyEntity>()
                        .eq(LeaderRollingAggregationDailyEntity::getAsOfDate, s.getAsOfDate())
                        .eq(LeaderRollingAggregationDailyEntity::getLookbackDays, s.getLookbackDays())
                        .last("limit 1"));
        LeaderRollingAggregationDailyEntity row = existing == null
                ? new LeaderRollingAggregationDailyEntity()
                : existing;
        row.setAsOfDate(s.getAsOfDate())
                .setLookbackDays(s.getLookbackDays())
                .setWindowSize(s.getWindowSize())
                .setMinSamples(s.getMinSamples())
                .setMinWinRate(s.getMinWinRate())
                .setActiveLeaders(s.getActiveLeaders())
                .setEvaluatedCount(s.getEvaluatedCount())
                .setActiveCount(s.getActiveCount())
                .setCoolingCount(s.getCoolingCount())
                .setColdCount(s.getColdCount())
                .setCoolingRatio(s.getCoolingRatio())
                .setOverallWinRate(s.getOverallWinRate())
                .setHealthStatus(s.getHealthStatus())
                .setTopCoolingLeadersJson(s.getTopCoolingLeadersJson())
                .setComputedAt(s.getComputedAt() == null ? now : s.getComputedAt())
                .setUpdatedAt(now);
        if (existing == null) {
            row.setCreatedAt(now);
            aggregationDailyMapper.insert(row);
        } else {
            aggregationDailyMapper.updateById(row);
        }
    }

    // ───────────────────────── alert ─────────────────────────

    private void publishAlertIfNeeded(LeaderRollingAggregationSnapshot s, SmartWalletProperties.Audit audit) {
        if (HEALTH_OK.equals(s.getHealthStatus())) return;
        if (!audit.isAlertSinkEnabled() || auditAlertSink == null) return;
        try {
            String title = String.format(Locale.ROOT,
                    "Leader 滑动窗口胜率灰度健康 %s: cooling=%d/%d ratio=%s overallWin=%s lookback=%dd",
                    s.getHealthStatus(), s.getCoolingCount(), s.getEvaluatedCount(),
                    s.getCoolingRatio(), s.getOverallWinRate(), s.getLookbackDays());
            String message = String.format(Locale.ROOT,
                    "asOfDate=%s lookbackDays=%d windowSize=%d minSamples=%d minWinRate=%s "
                            + "activeLeaders=%d evaluated=%d active=%d cooling=%d cold=%d "
                            + "coolingRatio=%s overallWinRate=%s "
                            + "warnCoolingRatio=%s warnMinOverallWinRate=%s topCooling=%s",
                    s.getAsOfDate(), s.getLookbackDays(), s.getWindowSize(),
                    s.getMinSamples(), s.getMinWinRate(),
                    s.getActiveLeaders(), s.getEvaluatedCount(),
                    s.getActiveCount(), s.getCoolingCount(), s.getColdCount(),
                    s.getCoolingRatio(), s.getOverallWinRate(),
                    s.getWarnCoolingRatio(), s.getWarnMinOverallWinRate(),
                    s.getTopCoolingLeadersJson() == null ? "n/a" : s.getTopCoolingLeadersJson());
            // metricValue/threshold 选 coolingRatio vs warnCoolingRatio 作为主指标
            auditAlertSink.publish(AlertType.AUDIT_LEADER_ROLLING_HEALTH, title, message,
                    s.getCoolingRatio(), s.getWarnCoolingRatio());
        } catch (RuntimeException ex) {
            log.warn("{} auditAlertSink failed: {}", LOG_PREFIX, ex.toString(), ex);
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private static int defaultInt(Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    private static BigDecimal nonNullOr(BigDecimal v, BigDecimal fallback) {
        return v == null ? fallback : v;
    }

    private static ZoneId parseZone(String zone) {
        if (zone == null || zone.isBlank()) return ZoneId.of("Asia/Shanghai");
        try {
            return ZoneId.of(zone);
        } catch (RuntimeException ex) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    // ───────────────────────── inner types ─────────────────────────

    @Data
    @Builder
    public static class LeaderRollingAggregationSnapshot {
        private LocalDate asOfDate;
        private int lookbackDays;
        private int windowSize;
        private int minSamples;
        private BigDecimal minWinRate;
        private int activeLeaders;
        private int evaluatedCount;
        private int activeCount;
        private int coolingCount;
        private int coldCount;
        private BigDecimal coolingRatio;
        private BigDecimal overallWinRate;
        private String healthStatus;
        private String topCoolingLeadersJson;
        private BigDecimal warnCoolingRatio;
        private BigDecimal warnMinOverallWinRate;
        private Date computedAt;
    }

    /** internal row (避免暴露 web 端 LowWinRateLeader 跨模块依赖) */
    private static final class LeaderRow {
        final String leader;
        final int samples;
        final int wins;
        final int losses;
        final BigDecimal winRate;
        final BigDecimal totalPnlUsdc;
        final String status;

        LeaderRow(String leader, int samples, int wins, int losses, BigDecimal winRate,
                  BigDecimal totalPnlUsdc, String status) {
            this.leader = leader;
            this.samples = samples;
            this.wins = wins;
            this.losses = losses;
            this.winRate = winRate;
            this.totalPnlUsdc = totalPnlUsdc;
            this.status = status;
        }
    }
}
