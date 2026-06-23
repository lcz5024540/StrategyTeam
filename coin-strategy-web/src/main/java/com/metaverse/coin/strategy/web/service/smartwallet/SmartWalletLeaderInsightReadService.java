package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketInfoMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderDriftView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingView;
import com.metaverse.coin.strategy.web.model.smartwallet.LeaderRollingView.RecentClose;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Web BFF 端 W2-T2 leader rolling + W2-T6 leader drift 状态查询.
 *
 * 实现策略: 直查 DB; 算法与 job 端 LeaderRollingPerformanceService /
 * LeaderBehaviorDriftService 对齐(仅核心维度)。仅返回当前 snapshot, 不修改任何状态。
 *
 * 详见 docs/polymarket/copy-trading-improvement-decision-2026-05-16.md 第十一节.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartWalletLeaderInsightReadService {

    private static final int DEFAULT_WINDOW = 20;
    private static final int DEFAULT_MIN_SAMPLES = 8;
    private static final BigDecimal DEFAULT_MIN_WIN_RATE = new BigDecimal("0.45");
    private static final int DEFAULT_DRIFT_LOOKBACK_DAYS = 30;
    private static final int DEFAULT_DRIFT_MIN_SAMPLES = 10;
    private static final double DEFAULT_DRIFT_THRESHOLD = 0.40;
    private static final double DEFAULT_DRIFT_SEVERE = 0.60;
    private static final int DRIFT_PROFILE_LIMIT = 500;

    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletMarketInfoMapper marketInfoMapper;

    // ── W2-T2 leader rolling ────────────────────────────────────────

    /**
     * 取该 leader 最近 N 笔 ENTRY 单对应仓位的盈亏, 计算 win rate / 状态分档.
     */
    public LeaderRollingView queryLeaderRolling(String leaderAddress, Integer windowSize) {
        if (!StringUtils.hasText(leaderAddress)) {
            return LeaderRollingView.builder().leaderAddress(leaderAddress).status("DISABLED").reason("missing leaderAddress").build();
        }
        int window = windowSize != null && windowSize > 0 ? windowSize : DEFAULT_WINDOW;
        String addr = leaderAddress.toLowerCase();

        // 找该 leader 的 BUY ENTRY 订单, 按 filledAt 倒序取 window 个
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getWalletAddress, addr)
                        .eq(SmartWalletOrderEntity::getSide, "BUY")
                        .eq(SmartWalletOrderEntity::getOrderType, "ENTRY")
                        .isNotNull(SmartWalletOrderEntity::getPositionId)
                        .isNotNull(SmartWalletOrderEntity::getFilledAt)
                        .orderByDesc(SmartWalletOrderEntity::getFilledAt)
                        .last("limit " + (window * 3))); // 留余量, 因为后面要按 position closed 过滤

        if (orders.isEmpty()) {
            return LeaderRollingView.builder()
                    .leaderAddress(addr)
                    .status("COLD")
                    .reason("no recent ENTRY orders")
                    .windowSize(window).sampleCount(0).minSamples(DEFAULT_MIN_SAMPLES)
                    .build();
        }

        List<Long> positionIds = orders.stream()
                .map(SmartWalletOrderEntity::getPositionId)
                .filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        Map<Long, SmartWalletPositionEntity> closedPositions = new HashMap<>();
        positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getId, positionIds)
                        .in(SmartWalletPositionEntity::getStatus, List.of("CLOSED", "SETTLED", "CLAIMED")))
                .forEach(p -> closedPositions.put(p.getId(), p));

        // 取最近 window 个已关闭仓位
        List<RecentClose> closes = new ArrayList<>();
        int wins = 0, losses = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;
        Date earliest = null, latest = null;
        for (SmartWalletOrderEntity o : orders) {
            if (closes.size() >= window) break;
            SmartWalletPositionEntity p = closedPositions.get(o.getPositionId());
            if (p == null) continue;
            BigDecimal pnl = p.getRealizedPnlUsdc() == null ? BigDecimal.ZERO : p.getRealizedPnlUsdc();
            boolean win = pnl.signum() > 0;
            if (win) wins++; else losses++;
            totalPnl = totalPnl.add(pnl);
            Date closedAt = p.getClosedAt();
            if (closedAt != null) {
                if (earliest == null || closedAt.before(earliest)) earliest = closedAt;
                if (latest == null || closedAt.after(latest)) latest = closedAt;
            }
            closes.add(RecentClose.builder()
                    .positionId(p.getId())
                    .followTaskId(o.getFollowTaskId())
                    .marketId(p.getMarketId())
                    .outcome(p.getOutcome())
                    .realizedPnlUsdc(pnl)
                    .win(win)
                    .closedAt(closedAt)
                    .build());
        }

        int samples = closes.size();
        BigDecimal winRate = samples == 0 ? null
                : new BigDecimal(wins).divide(new BigDecimal(samples), 4, RoundingMode.HALF_UP);
        BigDecimal avgPnl = samples == 0 ? null
                : totalPnl.divide(new BigDecimal(samples), 4, RoundingMode.HALF_UP);

        String status;
        String reason;
        if (samples < DEFAULT_MIN_SAMPLES) {
            status = "COLD";
            reason = "samples=" + samples + " < minSamples=" + DEFAULT_MIN_SAMPLES;
        } else if (winRate.compareTo(DEFAULT_MIN_WIN_RATE) < 0) {
            status = "COOLING";
            reason = "winRate=" + winRate + " < threshold=" + DEFAULT_MIN_WIN_RATE;
        } else {
            status = "ACTIVE";
            reason = "winRate=" + winRate + " >= threshold=" + DEFAULT_MIN_WIN_RATE;
        }

        return LeaderRollingView.builder()
                .leaderAddress(addr)
                .status(status)
                .windowSize(window)
                .sampleCount(samples)
                .minSamples(DEFAULT_MIN_SAMPLES)
                .wins(wins).losses(losses)
                .winRate(winRate)
                .totalPnlUsdc(totalPnl.setScale(4, RoundingMode.HALF_UP))
                .avgPnlUsdc(avgPnl)
                .earliestClosedAt(earliest)
                .latestClosedAt(latest)
                .reason(reason)
                .recentCloses(closes)
                .build();
    }

    // ── W2-T6 leader drift ──────────────────────────────────────────

    /**
     * 计算 leader 7 维特征 centroid (size+side+5-cat one-hot), 与最近一笔比较 cosine 距离.
     * 与 job 端 LeaderBehaviorDriftService 算法对齐.
     */
    public LeaderDriftView queryLeaderDrift(String leaderAddress, Integer lookbackDays) {
        if (!StringUtils.hasText(leaderAddress)) {
            return LeaderDriftView.builder().leaderAddress(leaderAddress).status("DISABLED").reason("missing leaderAddress").build();
        }
        int lookback = lookbackDays != null && lookbackDays > 0 ? lookbackDays : DEFAULT_DRIFT_LOOKBACK_DAYS;
        String addr = leaderAddress.toLowerCase();
        Date now = new Date();
        Date since = new Date(now.getTime() - lookback * 86_400_000L);

        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .eq(SmartWalletTradeEventEntity::getWalletAddress, addr)
                        .ge(SmartWalletTradeEventEntity::getCreatedAt, since)
                        .orderByDesc(SmartWalletTradeEventEntity::getCreatedAt)
                        .last("limit " + DRIFT_PROFILE_LIMIT));

        if (events.isEmpty()) {
            return LeaderDriftView.builder()
                    .leaderAddress(addr).status("COLD_START")
                    .lookbackDays(lookback).sampleCount(0).minSamples(DEFAULT_DRIFT_MIN_SAMPLES)
                    .driftThreshold(DEFAULT_DRIFT_THRESHOLD).severeDriftThreshold(DEFAULT_DRIFT_SEVERE)
                    .reason("no trade events in lookback window")
                    .build();
        }

        // 批量取 marketInfo 用于 category one-hot
        List<String> marketIds = events.stream()
                .map(SmartWalletTradeEventEntity::getMarketId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<String, String> marketCategory = new HashMap<>();
        if (!marketIds.isEmpty()) {
            marketInfoMapper.selectList(new LambdaQueryWrapper<SmartWalletMarketInfoEntity>()
                            .in(SmartWalletMarketInfoEntity::getMarketId, marketIds))
                    .forEach(m -> marketCategory.put(m.getMarketId(), resolveCategory(m)));
        }

        // centroid: average of all 7-dim vectors
        double[] centroid = new double[7];
        for (SmartWalletTradeEventEntity e : events) {
            double[] v = vectorize(e, marketCategory.getOrDefault(e.getMarketId(), "OTHER"));
            for (int i = 0; i < 7; i++) centroid[i] += v[i];
        }
        for (int i = 0; i < 7; i++) centroid[i] /= events.size();

        SmartWalletTradeEventEntity last = events.get(0);
        double[] lastVec = vectorize(last, marketCategory.getOrDefault(last.getMarketId(), "OTHER"));
        Double distance = cosineDistance(centroid, lastVec);

        String status;
        String reason;
        if (events.size() < DEFAULT_DRIFT_MIN_SAMPLES) {
            status = "COLD_START";
            reason = "samples=" + events.size() + " < minSamples=" + DEFAULT_DRIFT_MIN_SAMPLES;
        } else if (distance == null) {
            status = "ACTIVE";
            reason = "distance NaN (degenerate vector)";
        } else if (distance >= DEFAULT_DRIFT_SEVERE) {
            status = "DRIFTED_SEVERE";
            reason = "cosineDistance=" + roundD(distance) + " >= severe=" + DEFAULT_DRIFT_SEVERE;
        } else if (distance >= DEFAULT_DRIFT_THRESHOLD) {
            status = "DRIFTED";
            reason = "cosineDistance=" + roundD(distance) + " >= threshold=" + DEFAULT_DRIFT_THRESHOLD;
        } else {
            status = "ACTIVE";
            reason = "cosineDistance=" + roundD(distance) + " < threshold=" + DEFAULT_DRIFT_THRESHOLD;
        }

        Date profileFrom = events.get(events.size() - 1).getCreatedAt();
        Date profileTo = events.get(0).getCreatedAt();

        return LeaderDriftView.builder()
                .leaderAddress(addr)
                .status(status)
                .lookbackDays(lookback)
                .sampleCount(events.size())
                .minSamples(DEFAULT_DRIFT_MIN_SAMPLES)
                .profileCentroid(centroid)
                .lastEventVector(lastVec)
                .cosineDistance(distance == null ? null : roundD(distance))
                .driftThreshold(DEFAULT_DRIFT_THRESHOLD)
                .severeDriftThreshold(DEFAULT_DRIFT_SEVERE)
                .profileFrom(profileFrom).profileTo(profileTo)
                .reason(reason)
                .build();
    }

    /**
     * 7 维特征向量 = [log10(sizeUsdc+1)/6, sideBuy(0/1), cat_POLITICS, cat_CRYPTO, cat_SPORTS, cat_WEATHER, cat_OTHER].
     */
    private double[] vectorize(SmartWalletTradeEventEntity e, String category) {
        double size = e.getSizeUsdc() == null ? 0.0 : e.getSizeUsdc().doubleValue();
        double sizeFeature = Math.log10(size + 1.0) / 6.0;
        double sideBuy = "BUY".equalsIgnoreCase(e.getSide()) ? 1.0 : 0.0;
        double catPol = "POLITICS".equals(category) ? 1.0 : 0.0;
        double catCrypto = "CRYPTO".equals(category) ? 1.0 : 0.0;
        double catSports = "SPORTS".equals(category) ? 1.0 : 0.0;
        double catWeather = "WEATHER".equals(category) ? 1.0 : 0.0;
        double catOther = !(catPol > 0 || catCrypto > 0 || catSports > 0 || catWeather > 0) ? 1.0 : 0.0;
        return new double[] { sizeFeature, sideBuy, catPol, catCrypto, catSports, catWeather, catOther };
    }

    private String resolveCategory(SmartWalletMarketInfoEntity m) {
        if (m.getNormalizedCategory() != null && !m.getNormalizedCategory().isBlank()) return m.getNormalizedCategory();
        if (m.getSourceCategory() != null && !m.getSourceCategory().isBlank()) return m.getSourceCategory();
        return "OTHER";
    }

    private Double cosineDistance(double[] a, double[] b) {
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) return null;
        double cos = dot / (Math.sqrt(na) * Math.sqrt(nb));
        // clamp 数值精度
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return 1.0 - cos;
    }

    private double roundD(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
