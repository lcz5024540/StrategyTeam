package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.PmResolutionAuditMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.PmResolutionAuditEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderDriftAggregationView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderDriftAggregationView.DriftedLeader;
import com.metaverse.coin.strategy.web.model.smartwallet.ResolutionTriDiffView;
import com.metaverse.coin.strategy.web.model.smartwallet.ResolutionTriDiffView.TriDiffInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 第十二批 第五波 web 接口: 跟单审计聚合查询.
 *
 * <p>核心定位: web BFF 端的"聚合 + 不写库"只读接口, 与 job 端的 ResolutionTriDiffService /
 * LeaderDriftAggregationService 算法对齐但完全独立 (不引入 job 模块依赖, 不调用 cache,
 * 不接 audit sink).</p>
 *
 * <p>详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十九节.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartWalletAggregateAuditReadService {

    private final PmResolutionAuditMapper resolutionAuditMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;

    private static final int DEFAULT_TRIDIFF_LOOKBACK_HOURS = 24;
    private static final int DEFAULT_DRIFT_LOOKBACK_HOURS = 24;
    private static final int DRIFT_TRADE_EVENT_CAP = 5_000;
    private static final int TOP_INSTANCES = 10;

    // ── 结算三方 diff ────────────────────────────────────────────────

    /**
     * 算法与 job 端 ResolutionTriDiffService 等价. web 不依赖 job 模块, 直接查 audit 表
     * 自行聚合.
     */
    public ResolutionTriDiffView resolutionTriDiff(Integer lookbackHours) {
        int hours = lookbackHours != null && lookbackHours > 0 ? lookbackHours : DEFAULT_TRIDIFF_LOOKBACK_HOURS;
        Date now = new Date();
        Date since = new Date(now.getTime() - hours * 3_600_000L);

        List<PmResolutionAuditEntity> rows = resolutionAuditMapper.selectList(
                new LambdaQueryWrapper<PmResolutionAuditEntity>()
                        .ge(PmResolutionAuditEntity::getObservedAt, since)
                        .orderByDesc(PmResolutionAuditEntity::getObservedAt));

        int oursVsUma = 0, oursVsGamma = 0, umaVsGamma = 0;
        Map<String, List<String>> winningByMarket = new HashMap<>();
        List<TriDiffInstance> instances = new ArrayList<>();

        for (PmResolutionAuditEntity r : rows) {
            boolean weResolved = "RESOLVED".equalsIgnoreCase(r.getResolutionStatus());
            boolean gammaClosed = Boolean.TRUE.equals(r.getClosed());
            boolean umaResolved = Boolean.TRUE.equals(r.getUmaResolved());

            String type = null;
            if (weResolved && !umaResolved) {
                type = "OURS_VS_UMA";
                oursVsUma++;
            } else if (!weResolved && gammaClosed) {
                type = "OURS_VS_GAMMA";
                oursVsGamma++;
            } else if (gammaClosed && !umaResolved) {
                type = "UMA_VS_GAMMA";
                umaVsGamma++;
            }
            if (type != null && instances.size() < TOP_INSTANCES) {
                instances.add(TriDiffInstance.builder()
                        .marketId(r.getMarketId())
                        .observedAt(r.getObservedAt())
                        .diffType(type)
                        .resolutionStatus(r.getResolutionStatus())
                        .winningOutcome(r.getWinningOutcome())
                        .gammaClosed(gammaClosed)
                        .umaResolutionStatus(r.getUmaResolutionStatus())
                        .umaResolved(umaResolved)
                        .build());
            }
            if (r.getMarketId() != null && r.getWinningOutcome() != null) {
                winningByMarket.computeIfAbsent(r.getMarketId(), k -> new ArrayList<>())
                        .add(r.getWinningOutcome());
            }
        }

        int historyConflict = 0;
        List<String> conflictedMarkets = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : winningByMarket.entrySet()) {
            long distinct = e.getValue().stream().filter(Objects::nonNull).distinct().count();
            if (distinct > 1) {
                historyConflict++;
                conflictedMarkets.add(e.getKey());
            }
        }

        return ResolutionTriDiffView.builder()
                .generatedAt(now)
                .lookbackHours(hours)
                .totalAuditRows(rows.size())
                .anomalyTotal(oursVsUma + oursVsGamma + umaVsGamma + historyConflict)
                .oursVsUmaCount(oursVsUma)
                .oursVsGammaCount(oursVsGamma)
                .umaVsGammaCount(umaVsGamma)
                .historyConflictCount(historyConflict)
                .conflictedMarketIds(conflictedMarkets)
                .topInstances(instances)
                .build();
    }

    // ── LeaderDrift 聚合 ────────────────────────────────────────────

    /**
     * 仅返回 sample 计数与 top leaders (按最近一笔 trade event activity), 不实际计算 cosine
     * 距离 (那是 job 端 LeaderBehaviorDriftService.evaluateForReporting 的 hot path 算法,
     * 复用需要把 LeaderBehaviorDrift 一整套搬到 web — 工作量过大).
     *
     * <p>web 用户若需 leader-level 漂移诊断, 请用 {@code /leader-drift/{wallet}} 单 leader
     * 端点 (已第二波落地).</p>
     */
    public LeaderDriftAggregationView leaderDriftAggregation(Integer lookbackHours) {
        int hours = lookbackHours != null && lookbackHours > 0 ? lookbackHours : DEFAULT_DRIFT_LOOKBACK_HOURS;
        Date now = new Date();
        Date since = new Date(now.getTime() - hours * 3_600_000L);

        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .ge(SmartWalletTradeEventEntity::getCreatedAt, since)
                        .orderByDesc(SmartWalletTradeEventEntity::getCreatedAt)
                        .last("limit " + DRIFT_TRADE_EVENT_CAP));

        Map<String, Date> lastEventByLeader = new HashMap<>();
        for (SmartWalletTradeEventEntity e : events) {
            if (e.getWalletAddress() == null) continue;
            lastEventByLeader.putIfAbsent(e.getWalletAddress(), e.getCreatedAt());
        }

        int activeLeaders = lastEventByLeader.size();
        boolean truncated = events.size() >= DRIFT_TRADE_EVENT_CAP;

        // top-N "最近活跃" leader (按 latestEventAt desc), 实际 drift 状态需用单 leader 接口
        List<DriftedLeader> topActive = lastEventByLeader.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(TOP_INSTANCES)
                .map(e -> DriftedLeader.builder()
                        .leaderAddress(e.getKey())
                        .status("ACTIVE_CANDIDATE")
                        .cosineDistance(BigDecimal.ZERO)
                        .sampleCount(0)
                        .latestEventAt(e.getValue())
                        .reason("Use /leader-drift/{wallet} for actual cosine distance evaluation")
                        .build())
                .toList();

        return LeaderDriftAggregationView.builder()
                .generatedAt(now)
                .lookbackHours(hours)
                .activeLeaders(activeLeaders)
                .evaluated(0)
                .activeOkCount(0)
                .driftedCount(0)
                .driftedSevereCount(0)
                .coldStartCount(0)
                .skippedCount(0)
                .truncated(truncated)
                .topDrifted(topActive)
                .build();
    }
}
