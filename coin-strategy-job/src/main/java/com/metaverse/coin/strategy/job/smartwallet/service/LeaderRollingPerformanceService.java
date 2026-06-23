package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * W2-T2: Leader 滑动窗口胜率门服务。
 *
 * 实现要点（与决议文档 copy-trading-improvement-decision-2026-05-16.md W2-T2 一致）：
 *  - 维护每个 leader 的最近 N 笔跟单 win/PnL 滑动窗口（N 由 leaderRollingWindowSize 决定）
 *  - 区分 "leader 原始胜率" 与 "我方跟单后实际胜率" — 以 **跟单后实际胜率** 为准：
 *    数据源：smart_wallet_order 中 walletAddress=leader 且 side=BUY 且 orderType=ENTRY，
 *           关联到 smart_wallet_position（status ∈ CLOSED/SETTLED/CLAIMED），用 realized_pnl_usdc 判定 win/loss
 *  - 状态机由窗口数据 + 配置阈值无状态地派生：
 *    samples &lt; leaderRollingMinSamples              → ACTIVE（冷启动豁免）
 *    winRate &gt;= leaderRollingMinWinRate              → ACTIVE
 *    winRate &lt; leaderRollingMinWinRate &amp;&amp; 最近 K 笔
 *       恢复期 winRate &gt;= leaderRollingCanaryMinWinRate
 *       且 K 笔已跨越 leaderRollingCoolingDays         → ACTIVE（自动恢复）
 *    连续 N 次 COOLING 复活失败                       → SUSPENDED
 *    其他                                            → COOLING
 *
 * 设计权衡：
 *  - 状态从数据推导而非外存，方便服务重启后立即可用，不需要新表 / 不需要 migration
 *  - 用进程内 TTL 缓存（默认 60s）避免对每个 leader 信号触发一次全量 join
 *  - 服务默认通过配置开关 enableLeaderRollingGate=false 关闭，先观察 W1-T4 影子记账数据后再启用
 *
 * 对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响评估见决议文档 W2-T2 节。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeaderRollingPerformanceService {

    private static final String LOG_PREFIX = "[LEADER_ROLLING]";
    private static final String ORDER_SIDE_BUY = "BUY";
    private static final String ORDER_TYPE_ENTRY = "ENTRY";
    private static final Set<String> SETTLED_STATUSES = Set.of("CLOSED", "SETTLED", "CLAIMED");
    private static final int DEFAULT_WINDOW_SIZE = 20;

    private final SmartWalletProperties properties;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletPositionMapper positionMapper;

    private final Map<String, CachedState> cache = new ConcurrentHashMap<>();

    /**
     * 评估某个 leader 当前的状态。门控关闭或地址为空时返回 ACTIVE（不阻塞下单）。
     */
    public LeaderRollingState evaluate(String leaderAddress) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null || !risk.isEnableLeaderRollingGate()) {
            return LeaderRollingState.disabled();
        }
        if (!StringUtils.hasText(leaderAddress)) {
            return LeaderRollingState.disabled();
        }
        String key = normalize(leaderAddress);
        long ttlMs = Math.max(1_000L, risk.getLeaderRollingCacheTtlMs());
        long now = System.currentTimeMillis();
        CachedState cached = cache.get(key);
        if (cached != null && cached.expiresAt > now) {
            return cached.state;
        }
        LeaderRollingState fresh = computeState(key, risk);
        cache.put(key, new CachedState(fresh, now + ttlMs));
        return fresh;
    }

    /**
     * 给定 leader 的 stake 倍率调整建议：
     *  ACTIVE     → 1.0
     *  COOLING    → leaderRollingCoolingStakeMultiplier（默认 0.05 = 5% Kelly）
     *  SUSPENDED  → 0.0（建议直接拒绝，调用方据此决定）
     */
    public BigDecimal stakeMultiplier(LeaderRollingState state) {
        if (state == null || !state.isGateEnabled()) {
            return BigDecimal.ONE;
        }
        switch (state.getStatus()) {
            case ACTIVE:
                return BigDecimal.ONE;
            case COOLING:
                BigDecimal multiplier = properties.getSignalRisk().getLeaderRollingCoolingStakeMultiplier();
                return multiplier == null || multiplier.signum() < 0 ? BigDecimal.ZERO : multiplier;
            case SUSPENDED:
                return BigDecimal.ZERO;
            default:
                return BigDecimal.ONE;
        }
    }

    /** 强制清缓存，运维/测试用 */
    public void invalidate(String leaderAddress) {
        if (!StringUtils.hasText(leaderAddress)) {
            cache.clear();
            return;
        }
        cache.remove(normalize(leaderAddress));
    }

    // ───────────────────────── internal ─────────────────────────

    private LeaderRollingState computeState(String leaderAddress, SmartWalletProperties.SignalRisk risk) {
        int windowSize = defaultInt(risk.getLeaderRollingWindowSize(), DEFAULT_WINDOW_SIZE);
        if (windowSize <= 0) {
            return LeaderRollingState.disabled();
        }
        int minSamples = Math.max(0, defaultInt(risk.getLeaderRollingMinSamples(), 0));
        BigDecimal minWinRate = safe(risk.getLeaderRollingMinWinRate());
        BigDecimal canaryMinWinRate = safe(risk.getLeaderRollingCanaryMinWinRate());
        int canaryProbeCount = Math.max(1, defaultInt(risk.getLeaderRollingCanaryProbeCount(), 5));
        int coolingDays = Math.max(0, defaultInt(risk.getLeaderRollingCoolingDays(), 7));
        int suspendAfterFails = Math.max(1, defaultInt(risk.getLeaderRollingSuspendAfterFailedRecoveries(), 3));
        int suspendDays = Math.max(0, defaultInt(risk.getLeaderRollingSuspendDays(), 30));
        BigDecimal winRoiThreshold = safe(risk.getLeaderRollingWinRoiThreshold());

        List<LeaderTrade> window = loadWindow(leaderAddress, windowSize);
        if (window.size() < minSamples) {
            return LeaderRollingState.cold(window.size(), minSamples,
                    String.format(Locale.ROOT,
                            "samples=%d < minSamples=%d → ACTIVE(cold-start)", window.size(), minSamples));
        }
        if (window.isEmpty()) {
            return LeaderRollingState.cold(0, minSamples, "no closed positions → ACTIVE(cold-start)");
        }

        int wins = 0;
        int losses = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (LeaderTrade t : window) {
            totalPnl = totalPnl.add(t.realizedPnlUsdc());
            if (t.realizedPnlUsdc().compareTo(winRoiThreshold) > 0) {
                wins++;
            } else {
                losses++;
            }
        }
        BigDecimal winRate = window.isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(window.size()), 4, RoundingMode.HALF_UP);

        LeaderRollingMetrics metrics = new LeaderRollingMetrics(
                leaderAddress,
                window.size(),
                wins,
                losses,
                winRate,
                totalPnl.setScale(4, RoundingMode.HALF_UP),
                window.get(window.size() - 1).closedAt(),
                window.get(0).closedAt());

        if (winRate.compareTo(minWinRate) >= 0) {
            return LeaderRollingState.active(metrics,
                    String.format(Locale.ROOT, "winRate=%s >= min=%s → ACTIVE",
                            winRate.toPlainString(), minWinRate.toPlainString()));
        }

        // winRate 不达标，按"近 K 笔恢复期"判断是否仍有恢复希望：
        //  - 取最新 canaryProbeCount 笔的子窗口
        //  - 若子窗口 winRate >= canaryMinWinRate AND 子窗口起点距今 >= coolingDays → 已恢复，回 ACTIVE
        //  - 否则 COOLING
        List<LeaderTrade> canary = window.subList(0, Math.min(canaryProbeCount, window.size()));
        int canaryWins = 0;
        for (LeaderTrade t : canary) {
            if (t.realizedPnlUsdc().compareTo(winRoiThreshold) > 0) {
                canaryWins++;
            }
        }
        BigDecimal canaryRate = canary.isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(canaryWins).divide(BigDecimal.valueOf(canary.size()), 4, RoundingMode.HALF_UP);
        boolean canaryOk = canary.size() >= canaryProbeCount && canaryRate.compareTo(canaryMinWinRate) >= 0;

        long coolingMs = coolingDays * 86_400_000L;
        long now = System.currentTimeMillis();
        Date earliestCanaryTrade = canary.isEmpty()
                ? null
                : canary.get(canary.size() - 1).closedAt();
        boolean canaryAged = earliestCanaryTrade != null
                && (now - earliestCanaryTrade.getTime()) >= coolingMs;

        if (canaryOk && canaryAged) {
            return LeaderRollingState.recovered(metrics, canaryRate, canaryProbeCount,
                    String.format(Locale.ROOT,
                            "winRate=%s < min=%s BUT canaryRate=%s >= %s over %d trades aged %dd → ACTIVE(recovered)",
                            winRate.toPlainString(), minWinRate.toPlainString(),
                            canaryRate.toPlainString(), canaryMinWinRate.toPlainString(),
                            canaryProbeCount, coolingDays));
        }

        // 决定 COOLING vs SUSPENDED：
        //  统计窗口内 "连续 COOLING 段" 的数量 — 用"近 suspendAfterFails * canaryProbeCount 笔仍未达标"做近似
        int consecutiveFailWindow = Math.min(window.size(), suspendAfterFails * canaryProbeCount);
        List<LeaderTrade> persistFail = window.subList(0, consecutiveFailWindow);
        int persistWins = 0;
        for (LeaderTrade t : persistFail) {
            if (t.realizedPnlUsdc().compareTo(winRoiThreshold) > 0) {
                persistWins++;
            }
        }
        BigDecimal persistWinRate = persistFail.isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(persistWins).divide(BigDecimal.valueOf(persistFail.size()), 4, RoundingMode.HALF_UP);
        if (persistFail.size() >= suspendAfterFails * canaryProbeCount
                && persistWinRate.compareTo(canaryMinWinRate) < 0) {
            long suspendMs = suspendDays * 86_400_000L;
            Date suspendUntil = new Date(now + suspendMs);
            return LeaderRollingState.suspended(metrics, suspendUntil,
                    String.format(Locale.ROOT,
                            "persistent failure (last %d trades winRate=%s < canaryMin=%s) → SUSPENDED until %s",
                            persistFail.size(), persistWinRate.toPlainString(),
                            canaryMinWinRate.toPlainString(), suspendUntil));
        }

        return LeaderRollingState.cooling(metrics, canaryRate, canaryProbeCount, coolingDays,
                String.format(Locale.ROOT,
                        "winRate=%s < min=%s → COOLING (canaryRate=%s/%d, %dd)",
                        winRate.toPlainString(), minWinRate.toPlainString(),
                        canaryRate.toPlainString(), canaryProbeCount, coolingDays));
    }

    private List<LeaderTrade> loadWindow(String leaderAddress, int windowSize) {
        // 1) 拉 leader 的 entry 订单（side=BUY, orderType=ENTRY）
        List<SmartWalletOrderEntity> entryOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getWalletAddress, leaderAddress)
                .eq(SmartWalletOrderEntity::getSide, ORDER_SIDE_BUY)
                .eq(SmartWalletOrderEntity::getOrderType, ORDER_TYPE_ENTRY)
                .isNotNull(SmartWalletOrderEntity::getPositionId)
                .orderByDesc(SmartWalletOrderEntity::getFilledAt)
                .last("limit " + Math.max(windowSize * 4, 100)));
        if (entryOrders.isEmpty()) {
            return List.of();
        }

        // 2) 拿到 positionId 集合（同一 positionId 仅取一次以避免 scale-up 重复计数）
        LinkedHashMap<Long, SmartWalletOrderEntity> uniqueEntryByPositionId = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : entryOrders) {
            if (order.getPositionId() == null) {
                continue;
            }
            uniqueEntryByPositionId.putIfAbsent(order.getPositionId(), order);
        }
        Set<Long> positionIds = new HashSet<>(uniqueEntryByPositionId.keySet());
        if (positionIds.isEmpty()) {
            return List.of();
        }

        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getId, positionIds)
                .in(SmartWalletPositionEntity::getStatus, SETTLED_STATUSES)
                .isNotNull(SmartWalletPositionEntity::getClosedAt));

        List<LeaderTrade> result = new ArrayList<>(positions.size());
        for (SmartWalletPositionEntity pos : positions) {
            if (pos.getClosedAt() == null) continue;
            BigDecimal pnl = safe(pos.getRealizedPnlUsdc());
            result.add(new LeaderTrade(pos.getId(), pos.getClosedAt(), pnl));
        }
        result.sort(Comparator.comparing(LeaderTrade::closedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (result.size() > windowSize) {
            return new ArrayList<>(result.subList(0, windowSize));
        }
        return result;
    }

    private static int defaultInt(Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    private static BigDecimal safe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String normalize(String addr) {
        return addr == null ? "" : addr.trim().toLowerCase(Locale.ROOT);
    }

    // ───────────────────────── inner types ─────────────────────────

    public enum LeaderRollingStatus {
        ACTIVE,
        COOLING,
        SUSPENDED
    }

    /**
     * 滑动窗口统计（最新一笔在 newestClosedAt）
     */
    public record LeaderRollingMetrics(
            String leaderAddress,
            int sampleCount,
            int wins,
            int losses,
            BigDecimal winRate,
            BigDecimal totalPnlUsdc,
            Date oldestClosedAt,
            Date newestClosedAt) {
    }

    /**
     * Leader 状态。gateEnabled=false 时不应该被调用方解释为拒绝。
     */
    public static final class LeaderRollingState {
        private final boolean gateEnabled;
        private final LeaderRollingStatus status;
        private final String reason;
        private final LeaderRollingMetrics metrics;
        private final Date suspendUntil;
        private final BigDecimal canaryWinRate;

        private LeaderRollingState(boolean gateEnabled, LeaderRollingStatus status, String reason,
                                   LeaderRollingMetrics metrics, Date suspendUntil, BigDecimal canaryWinRate) {
            this.gateEnabled = gateEnabled;
            this.status = status;
            this.reason = reason;
            this.metrics = metrics;
            this.suspendUntil = suspendUntil;
            this.canaryWinRate = canaryWinRate;
        }

        public static LeaderRollingState disabled() {
            return new LeaderRollingState(false, LeaderRollingStatus.ACTIVE, "leader-rolling gate disabled",
                    null, null, null);
        }

        public static LeaderRollingState cold(int samples, int minSamples, String reason) {
            return new LeaderRollingState(true, LeaderRollingStatus.ACTIVE, reason, null, null, null);
        }

        public static LeaderRollingState active(LeaderRollingMetrics metrics, String reason) {
            return new LeaderRollingState(true, LeaderRollingStatus.ACTIVE, reason, metrics, null, null);
        }

        public static LeaderRollingState recovered(LeaderRollingMetrics metrics, BigDecimal canaryRate,
                                                   int probeCount, String reason) {
            return new LeaderRollingState(true, LeaderRollingStatus.ACTIVE, reason, metrics, null, canaryRate);
        }

        public static LeaderRollingState cooling(LeaderRollingMetrics metrics, BigDecimal canaryRate,
                                                 int probeCount, int coolingDays, String reason) {
            return new LeaderRollingState(true, LeaderRollingStatus.COOLING, reason, metrics, null, canaryRate);
        }

        public static LeaderRollingState suspended(LeaderRollingMetrics metrics, Date suspendUntil, String reason) {
            return new LeaderRollingState(true, LeaderRollingStatus.SUSPENDED, reason, metrics, suspendUntil, null);
        }

        public boolean isGateEnabled() {
            return gateEnabled;
        }

        public LeaderRollingStatus getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }

        public LeaderRollingMetrics getMetrics() {
            return metrics;
        }

        public Date getSuspendUntil() {
            return suspendUntil;
        }

        public BigDecimal getCanaryWinRate() {
            return canaryWinRate;
        }
    }

    private record LeaderTrade(Long positionId, Date closedAt, BigDecimal realizedPnlUsdc) {
    }

    private record CachedState(LeaderRollingState state, long expiresAt) {
    }
}
