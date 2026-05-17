package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.behavior.CentroidDriftDetector;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * W2-T6: Leader 行为漂移门服务。
 *
 * <p>实现要点（与决议文档 copy-trading-improvement-decision-2026-05-16.md W2-T6 一致）：
 * <ul>
 *   <li>对每个 leader 维护"历史行为画像向量"（最近 N 天 trade event 的特征均值）。</li>
 *   <li>当 leader 当前下单的行为特征与历史画像 cosine 距离 &gt; 阈值时，标记 DRIFTED，
 *       与 W2-T2 共享 COOLING 语义（降级仓位，仍可开单），重度漂移（severe 阈值）
 *       直接拒绝（语义等同 W2-T2 的 SUSPENDED）。</li>
 *   <li>状态从数据派生，不需要外存状态机 / migration。</li>
 *   <li>默认通过配置 enableLeaderDriftGate=false 关闭，先观察 W1-T4 影子记账数据再启用。</li>
 * </ul>
 *
 * <p>特征向量定义（7 维）：
 * <ol>
 *   <li>{@code sizeBucket} — log10(sizeUsdc + 1) 的归一化值（缩放到 [0, 1]，6 美元约 0.13、1000 美元约 0.5）</li>
 *   <li>{@code sideBuy} — BUY=1, SELL=0</li>
 *   <li>{@code categoryPolitics} — 该笔交易所在市场 normalizedCategory == POLITICS 时为 1，否则 0</li>
 *   <li>{@code categoryCrypto}</li>
 *   <li>{@code categorySports}</li>
 *   <li>{@code categoryWeather}</li>
 *   <li>{@code categoryOther}</li>
 * </ol>
 *
 * <p>历史画像 = 最近 leaderDriftLookbackDays 天 trade event 特征向量的"均值（centroid）"。
 * 当前下单特征 = 该笔 event 单独的特征向量。
 *
 * <p>cosine 距离 = 1 - cosine 相似度 = 1 - (V·C) / (|V|·|C|)。距离范围 [0, 2]，
 * 实际使用中绝大多数落在 [0, 1]。
 *
 * <p>对开单效率 / 资金使用率 / 是否会开单 / 盈亏点 / 胜率影响评估见决议文档 W2-T6 节。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LeaderBehaviorDriftService {

    private static final String LOG_PREFIX = "[LEADER_DRIFT]";

    /**
     * 第十六批 (Tier B 验证): 7 维特征向量化算法已移至通用 {@link SmartWalletTradeEventVectorizer}.
     * 维度常量从 vectorizer 引用, 保持与历史 centroid 完全兼容.
     */
    private static final int FEATURE_DIM = SmartWalletTradeEventVectorizer.FEATURE_DIM;

    /** 共享单例 (无状态), 节省 hot path 实例化开销. */
    private static final SmartWalletTradeEventVectorizer VECTORIZER = new SmartWalletTradeEventVectorizer();

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletMarketInfoService marketInfoService;
    /** 方案 B (W2-T6 优化): 优先从 BehaviorProfile snapshot 表读 centroid, 避免每次 cache miss 重算 500 trade_event. */
    private final SmartWalletBehaviorProfileMapper behaviorProfileMapper;

    /** leader 画像缓存（key=lowerCase address） */
    private final Map<String, CachedProfile> profileCache = new ConcurrentHashMap<>();

    /**
     * 评估某笔 leader 信号是否触发行为漂移门。
     *
     * @param leaderAddress 信号 leader 地址
     * @param event         当前 trade event
     * @return 漂移状态决策（不抛异常；门控关闭或样本不足时返回 ACTIVE 即可放行）
     */
    public LeaderDriftState evaluate(String leaderAddress, SmartWalletTradeEventEntity event) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null || !risk.isEnableLeaderDriftGate()) {
            return LeaderDriftState.disabled();
        }
        return computeInternal(risk, leaderAddress, event, false);
    }

    /**
     * 仅供 W2-T6 LeaderDriftAggregationJob 等聚合任务使用：无论 {@code enableLeaderDriftGate}
     * 配置如何，都强制计算当前 leader 的画像偏离度。本路径不参与下单决策，纯只读，主要价值在于
     * 让运维 / 量化研究员在「灰度启用 leader-drift-gate 之前」即可观察现网各 leader 的漂移分布
     * 数据 —— 与 W1-T4 影子记账周报互为补充。
     *
     * <p>与 {@link #evaluate} 的差异：
     * <ul>
     *   <li>跳过 {@code enableLeaderDriftGate} 检查（始终走完整算法）。</li>
     *   <li>未配置 SignalRisk 时返回 {@link LeaderDriftState#disabled()} 兜底，调用方据此跳过。</li>
     *   <li>样本数不足 / 算法异常时，与 {@link #evaluate} 返回相同的 ACTIVE/cold-start 形态，
     *       不会抛出 RuntimeException。</li>
     * </ul>
     *
     * @param leaderAddress 信号 leader 地址
     * @param event         待评估的 trade event（通常是该 leader 最新一笔）
     * @return drift 状态（gateEnabled=true 时由调用方按 status 自行处置）
     */
    public LeaderDriftState evaluateForReporting(String leaderAddress, SmartWalletTradeEventEntity event) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        if (risk == null) {
            return LeaderDriftState.disabled();
        }
        return computeInternal(risk, leaderAddress, event, true);
    }

    private LeaderDriftState computeInternal(SmartWalletProperties.SignalRisk risk,
                                             String leaderAddress,
                                             SmartWalletTradeEventEntity event,
                                             boolean forReporting) {
        if (!StringUtils.hasText(leaderAddress) || event == null) {
            return LeaderDriftState.disabled();
        }
        try {
            String key = leaderAddress.trim().toLowerCase(Locale.ROOT);
            int lookbackDays = Math.max(1, defaultInt(risk.getLeaderDriftLookbackDays(), 30));
            int minSamples = Math.max(1, defaultInt(risk.getLeaderDriftMinSamples(), 10));
            double driftThreshold = positive(risk.getLeaderDriftCosineThreshold(), 0.40);
            double severeThreshold = positive(risk.getLeaderDriftSevereCosineThreshold(), 0.60);
            long ttlMs = Math.max(1_000L, risk.getLeaderDriftCacheTtlMs());

            CachedProfile cached = profileCache.get(key);
            long now = System.currentTimeMillis();
            if (cached == null || cached.expiresAt <= now) {
                cached = loadProfile(key, lookbackDays, now + ttlMs);
                profileCache.put(key, cached);
            }
            if (cached.sampleCount < minSamples) {
                return LeaderDriftState.coldStart(cached.sampleCount, minSamples);
            }

            double[] currentVector = featureVector(event);
            double distance = cosineDistance(cached.centroid, currentVector);
            if (Double.isNaN(distance) || Double.isInfinite(distance)) {
                // 极端情况（向量全 0）不阻塞下单 / 报告侧也返回 cold-start 形态以便上层 skip
                return LeaderDriftState.coldStart(cached.sampleCount, minSamples);
            }
            BigDecimal distScaled = BigDecimal.valueOf(distance).setScale(4, RoundingMode.HALF_UP);
            LeaderDriftMetrics metrics = new LeaderDriftMetrics(
                    leaderAddress, cached.sampleCount, distScaled,
                    BigDecimal.valueOf(driftThreshold).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(severeThreshold).setScale(4, RoundingMode.HALF_UP));

            String pathTag = forReporting ? "[report]" : "";
            if (distance >= severeThreshold) {
                String reason = String.format(Locale.ROOT,
                        "%scosineDistance=%.4f >= severe=%.4f over %d historical trades → DRIFTED_SEVERE (reject-equivalent to SUSPENDED)",
                        pathTag, distance, severeThreshold, cached.sampleCount);
                return LeaderDriftState.severeDrift(metrics, reason);
            }
            if (distance >= driftThreshold) {
                String reason = String.format(Locale.ROOT,
                        "%scosineDistance=%.4f >= threshold=%.4f over %d historical trades → DRIFTED (cooling stake reduction)",
                        pathTag, distance, driftThreshold, cached.sampleCount);
                return LeaderDriftState.drifted(metrics, reason);
            }
            String reason = String.format(Locale.ROOT,
                    "%scosineDistance=%.4f < threshold=%.4f over %d historical trades → ACTIVE",
                    pathTag, distance, driftThreshold, cached.sampleCount);
            return LeaderDriftState.active(metrics, reason);
        } catch (Exception e) {
            log.warn("{} evaluate failed for leader={} forReporting={} reason={}",
                    LOG_PREFIX, leaderAddress, forReporting, e.getMessage());
            return LeaderDriftState.disabled();
        }
    }

    /**
     * Drifted 状态下的 stake 倍率。
     */
    public BigDecimal stakeMultiplier(LeaderDriftState state) {
        if (state == null || !state.isGateEnabled()) {
            return BigDecimal.ONE;
        }
        switch (state.getStatus()) {
            case DRIFTED:
                BigDecimal multiplier = properties.getSignalRisk().getLeaderDriftStakeMultiplier();
                return multiplier == null || multiplier.signum() < 0 ? BigDecimal.ZERO : multiplier;
            case DRIFTED_SEVERE:
                return BigDecimal.ZERO;
            case ACTIVE:
            default:
                return BigDecimal.ONE;
        }
    }

    /** 测试 / 运维使用：清缓存。 */
    public void invalidate(String leaderAddress) {
        if (!StringUtils.hasText(leaderAddress)) {
            profileCache.clear();
            return;
        }
        profileCache.remove(leaderAddress.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 方案 B (W2-T6 优化) 公共 API: 强制重新聚合该 leader 的 centroid 并序列化为 JSON,
     * 供 {@link BehaviorProfileSnapshotJob} 在 upsert profile 时一并写入
     * {@code smart_wallet_behavior_profile.behavior_vector_json}.
     *
     * <p>不依赖 {@code enableLeaderDriftGate} 配置; 即便 gate 关闭也算 (与
     * {@link #evaluateForReporting} 同源思路, 让运维可以在启用门控前看到分布数据).
     * 失败返回 null, 调用方应跳过本次写入.</p>
     *
     * @param leaderAddress leader 钱包地址
     * @param lookbackDays  画像聚合窗口 (天). null 时使用配置 leaderDriftLookbackDays (默认 30).
     * @return JSON 字符串 {@code {"centroid":[...7 doubles...],"sampleCount":N}};
     *         sampleCount=0 时也会返回 (运维可据此判断"无近期 trade event").
     */
    public String computeProfileVectorJson(String leaderAddress, Integer lookbackDays) {
        if (!StringUtils.hasText(leaderAddress)) return null;
        try {
            SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
            int days = lookbackDays != null && lookbackDays > 0
                    ? lookbackDays
                    : (risk == null ? 30 : Math.max(1, defaultInt(risk.getLeaderDriftLookbackDays(), 30)));
            CachedProfile cp = loadProfileFromTradeEvents(
                    leaderAddress.trim().toLowerCase(Locale.ROOT), days, Long.MAX_VALUE);
            return serializeCentroid(cp);
        } catch (RuntimeException ex) {
            log.warn("{} computeProfileVectorJson failed leader={} reason={}",
                    LOG_PREFIX, leaderAddress, ex.toString());
            return null;
        }
    }

    /** 序列化 centroid 为紧凑 JSON. */
    private String serializeCentroid(CachedProfile cp) {
        if (cp == null || cp.centroid == null) return null;
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"centroid\":[");
        for (int i = 0; i < cp.centroid.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(cp.centroid[i]));
        }
        sb.append("],\"sampleCount\":").append(cp.sampleCount).append('}');
        return sb.toString();
    }

    /** 反序列化 (容错). 失败 / 维度不符 / 字段缺失均返回 null. */
    private CachedProfile deserializeCentroid(String json, long expiresAt) {
        if (json == null || json.isBlank()) return null;
        try {
            int centroidStart = json.indexOf("[");
            int centroidEnd = json.indexOf("]", centroidStart + 1);
            if (centroidStart < 0 || centroidEnd <= centroidStart) return null;
            String[] parts = json.substring(centroidStart + 1, centroidEnd).split(",");
            if (parts.length != FEATURE_DIM) return null;
            double[] arr = new double[FEATURE_DIM];
            for (int i = 0; i < FEATURE_DIM; i++) {
                arr[i] = Double.parseDouble(parts[i].trim());
            }
            int sampleCount = 0;
            int scIdx = json.indexOf("\"sampleCount\"");
            if (scIdx > 0) {
                int colon = json.indexOf(":", scIdx);
                int end = json.indexOf("}", colon);
                if (colon > 0 && end > colon) {
                    String numStr = json.substring(colon + 1, end).trim();
                    if (numStr.endsWith(",")) numStr = numStr.substring(0, numStr.length() - 1);
                    sampleCount = Integer.parseInt(numStr);
                }
            }
            return new CachedProfile(arr, sampleCount, expiresAt);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        // 紧凑表示, 最多 6 位小数, 去掉无意义尾随零
        return BigDecimal.valueOf(d)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    // ──────────────────────────── internal helpers ────────────────────────────

    private CachedProfile loadProfile(String leaderAddress, int lookbackDays, long expiresAt) {
        // 方案 B (W2-T6 优化): 优先从 smart_wallet_behavior_profile.behavior_vector_json 读取,
        // 让 BehaviorProfileSnapshotJob (10min/次) 担任 centroid 维护方; Drift hot path 走 O(1) row query.
        // 失败或陈旧时无声 fallback 到下方现有的 trade_event 全量聚合算法.
        CachedProfile snapshotHit = tryLoadFromBehaviorProfile(leaderAddress, lookbackDays, expiresAt);
        if (snapshotHit != null) {
            return snapshotHit;
        }
        return loadProfileFromTradeEvents(leaderAddress, lookbackDays, expiresAt);
    }

    /**
     * 方案 B: 从 BehaviorProfile snapshot 表读取已序列化的 centroid.
     * 返回 null 表示需要 fallback (缺数据 / 陈旧 / 解析失败).
     */
    private CachedProfile tryLoadFromBehaviorProfile(String leaderAddress, int lookbackDays, long expiresAt) {
        try {
            SmartWalletBehaviorProfileEntity entity = behaviorProfileMapper.selectOne(
                    new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                            .eq(SmartWalletBehaviorProfileEntity::getWalletAddress, leaderAddress)
                            .last("limit 1"));
            if (entity == null) return null;
            String json = entity.getBehaviorVectorJson();
            if (!StringUtils.hasText(json)) return null;
            // 陈旧度: 比 lookbackDays 滚动窗口的 1/10 还早就视为 stale, 兜底 1h.
            Date computedAt = entity.getBehaviorVectorComputedAt();
            long staleMs = Math.max(3_600_000L, lookbackDays * 86_400_000L / 10L);
            if (computedAt == null
                    || System.currentTimeMillis() - computedAt.getTime() > staleMs) {
                return null;
            }
            return deserializeCentroid(json, expiresAt);
        } catch (RuntimeException ex) {
            log.debug("{} tryLoadFromBehaviorProfile fallback leader={} reason={}",
                    LOG_PREFIX, leaderAddress, ex.toString());
            return null;
        }
    }

    /** 现有的 trade_event 聚合算法 (保留作为 fallback path). */
    private CachedProfile loadProfileFromTradeEvents(String leaderAddress, int lookbackDays, long expiresAt) {
        long now = System.currentTimeMillis();
        Date since = new Date(now - lookbackDays * 86_400_000L);
        List<SmartWalletTradeEventEntity> events = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .eq(SmartWalletTradeEventEntity::getWalletAddress, leaderAddress)
                        .ge(SmartWalletTradeEventEntity::getCreatedAt, since)
                        .orderByDesc(SmartWalletTradeEventEntity::getCreatedAt)
                        .last("limit 500"));
        if (events == null || events.isEmpty()) {
            return new CachedProfile(new double[FEATURE_DIM], 0, expiresAt);
        }
        // 用 batchListByMarketIds 一次性拉 marketInfo，避免逐条 RPC
        Map<String, SmartWalletMarketInfoEntity> marketInfoByMarketId = new HashMap<>();
        try {
            List<String> distinctMarketIds = events.stream()
                    .map(SmartWalletTradeEventEntity::getMarketId)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            Map<String, List<SmartWalletMarketInfoEntity>> batch =
                    marketInfoService.batchListByMarketIds(distinctMarketIds);
            for (Map.Entry<String, List<SmartWalletMarketInfoEntity>> entry : batch.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    marketInfoByMarketId.put(entry.getKey(), entry.getValue().get(0));
                }
            }
        } catch (Exception e) {
            // 单条降级：缺 marketInfo 时该笔 category 落入 OTHER bucket
            log.warn("{} loadProfile batchListByMarketIds failed leader={} reason={}",
                    LOG_PREFIX, leaderAddress, e.getMessage());
        }

        double[] sum = new double[FEATURE_DIM];
        int count = 0;
        for (SmartWalletTradeEventEntity e : events) {
            SmartWalletMarketInfoEntity info = marketInfoByMarketId.get(
                    e.getMarketId() == null ? null : e.getMarketId());
            double[] v = featureVector(e, info);
            for (int i = 0; i < FEATURE_DIM; i++) {
                sum[i] += v[i];
            }
            count++;
        }
        if (count == 0) {
            return new CachedProfile(new double[FEATURE_DIM], 0, expiresAt);
        }
        double[] centroid = new double[FEATURE_DIM];
        for (int i = 0; i < FEATURE_DIM; i++) {
            centroid[i] = sum[i] / count;
        }
        return new CachedProfile(centroid, count, expiresAt);
    }

    /**
     * 第十六批 (Tier B 验证): 单参版本 — 在 hot path / aggregate Job 中按 event.marketId
     * 查 marketInfo 后, 委托给共享 {@link #VECTORIZER}.
     */
    private double[] featureVector(SmartWalletTradeEventEntity event) {
        SmartWalletMarketInfoEntity info = null;
        try {
            if (event != null && StringUtils.hasText(event.getMarketId())) {
                info = marketInfoService.getByMarketIdAndOutcome(event.getMarketId(), event.getOutcome());
            }
        } catch (Exception ex) {
            // 降级：缺 marketInfo 时该笔 category 落入 OTHER bucket (vectorizer 内部已处理)
        }
        return VECTORIZER.vectorize(event, info);
    }

    /**
     * 第十六批 (Tier B 验证): 双参版本 — 直接委托给共享 {@link #VECTORIZER}.
     * batchListByMarketIds 已 prefetch marketInfo 的 hot loop 使用本版本避免 N 次查询.
     */
    private double[] featureVector(SmartWalletTradeEventEntity event, SmartWalletMarketInfoEntity marketInfo) {
        return VECTORIZER.vectorize(event, marketInfo);
    }

    /**
     * 第十六批 (Tier B 验证): 委托到 common-service 的通用 cosine 距离实现, 验证 Tier B 抽取的真实复用性.
     * 行为与原实现等价 ({@link CentroidDriftDetector} 内部相同的数值钳位 + NaN 处理).
     */
    private static double cosineDistance(double[] a, double[] b) {
        return CentroidDriftDetector.cosineDistance(a, b);
    }

    private static int defaultInt(Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    private static double positive(BigDecimal v, double fallback) {
        if (v == null || v.signum() <= 0) return fallback;
        return v.doubleValue();
    }

    // ──────────────────────────── inner types ────────────────────────────

    public enum LeaderDriftStatus {
        ACTIVE,
        DRIFTED,
        DRIFTED_SEVERE
    }

    public record LeaderDriftMetrics(
            String leaderAddress,
            int sampleCount,
            BigDecimal cosineDistance,
            BigDecimal threshold,
            BigDecimal severeThreshold) {
    }

    public static final class LeaderDriftState {
        private final boolean gateEnabled;
        private final LeaderDriftStatus status;
        private final String reason;
        private final LeaderDriftMetrics metrics;

        private LeaderDriftState(boolean gateEnabled, LeaderDriftStatus status,
                                 String reason, LeaderDriftMetrics metrics) {
            this.gateEnabled = gateEnabled;
            this.status = status;
            this.reason = reason;
            this.metrics = metrics;
        }

        public static LeaderDriftState disabled() {
            return new LeaderDriftState(false, LeaderDriftStatus.ACTIVE, "leader-drift gate disabled", null);
        }

        public static LeaderDriftState coldStart(int samples, int minSamples) {
            String reason = String.format(Locale.ROOT,
                    "samples=%d < minSamples=%d → ACTIVE(cold-start)", samples, minSamples);
            return new LeaderDriftState(true, LeaderDriftStatus.ACTIVE, reason, null);
        }

        public static LeaderDriftState active(LeaderDriftMetrics metrics, String reason) {
            return new LeaderDriftState(true, LeaderDriftStatus.ACTIVE, reason, metrics);
        }

        public static LeaderDriftState drifted(LeaderDriftMetrics metrics, String reason) {
            return new LeaderDriftState(true, LeaderDriftStatus.DRIFTED, reason, metrics);
        }

        public static LeaderDriftState severeDrift(LeaderDriftMetrics metrics, String reason) {
            return new LeaderDriftState(true, LeaderDriftStatus.DRIFTED_SEVERE, reason, metrics);
        }

        public boolean isGateEnabled() {
            return gateEnabled;
        }

        public LeaderDriftStatus getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }

        public LeaderDriftMetrics getMetrics() {
            return metrics;
        }
    }

    private static final class CachedProfile {
        final double[] centroid;
        final int sampleCount;
        final long expiresAt;

        CachedProfile(double[] centroid, int sampleCount, long expiresAt) {
            this.centroid = centroid;
            this.sampleCount = sampleCount;
            this.expiresAt = expiresAt;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 第十六批 #3: 暴露通用 BehaviorVectorizer 实现, 验证 Tier B 接口的真实复用性.
    // hot path (featureVector) 当前保持不变;
    // 本 vectorizer 给跨策略调用方 (天气 / BTC 想用同维度体系) 或 web 端独立计算复用.
    // ──────────────────────────────────────────────────────────────────

    /**
     * 7 维 trade event 向量化器, 实现通用 {@link
     * com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer} 接口.
     */
    public com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer<SmartWalletTradeEventEntity>
            tradeEventVectorizer() {
        return new LeaderTradeEventVectorizer(marketInfoService);
    }

    /**
     * 公共 7 维向量化实现; 默认仅 driftService 内部用; 通过 {@link #tradeEventVectorizer()} 暴露.
     *
     * <p>本类是 W2-T6 内部跨策略调用方 (天气 / BTC) 的入口 — 包装 {@link SmartWalletTradeEventVectorizer}
     * 并自动用 {@link SmartWalletMarketInfoService} 按 event.marketId / outcome 查 marketInfo, 然后
     * 委托给共享的 vectorizer. 调用方拿到本类后调 {@code vectorize(event)} 即可获得 7 维向量.</p>
     */
    public static final class LeaderTradeEventVectorizer
            implements com.metaverse.coin.strategy.common.service.behavior.BehaviorVectorizer<SmartWalletTradeEventEntity> {

        private static final SmartWalletTradeEventVectorizer DELEGATE = new SmartWalletTradeEventVectorizer();

        private final SmartWalletMarketInfoService marketInfoService;

        public LeaderTradeEventVectorizer(SmartWalletMarketInfoService marketInfoService) {
            this.marketInfoService = marketInfoService;
        }

        @Override
        public int dimension() {
            return SmartWalletTradeEventVectorizer.FEATURE_DIM;
        }

        @Override
        public double[] vectorize(SmartWalletTradeEventEntity event) {
            SmartWalletMarketInfoEntity info = null;
            try {
                if (event != null && event.getMarketId() != null && !event.getMarketId().isBlank()) {
                    info = marketInfoService.getByMarketIdAndOutcome(event.getMarketId(), event.getOutcome());
                }
            } catch (Exception ex) {
                // 降级: 缺 marketInfo 时落 OTHER bucket (vectorizer 内部已处理)
            }
            return DELEGATE.vectorize(event, info);
        }
    }
}
