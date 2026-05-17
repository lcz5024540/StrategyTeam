package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualAllowlistMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletManualBlocklistMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualAllowlistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletManualBlocklistEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.web.model.CursorPageView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualAllowlistView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletManualBlocklistView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.web.model.smartwallet.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.web.model.smartwallet.WalletScreenerView;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SmartWalletProfileReadService {

    // 注意：数据库 source 字段值为 "POLYMARKET"。历史代码错写为 "POLYMARKET_COPY" 导致
    // /allowlist、/blocklist、/profiles、/profiles/screener 等接口全部返回空（白名单地址监控页面为空）。
    private static final String SOURCE = "POLYMARKET";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final long BEHAVIOR_PROFILE_STALE_MS = 24L * 3600_000L;

    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletBehaviorProfileMapper behaviorProfileMapper;
    private final SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    private final SmartWalletManualAllowlistMapper allowlistMapper;
    private final SmartWalletManualBlocklistMapper blocklistMapper;

    public SmartWalletProfileReadService(SmartWalletProfileMapper profileMapper,
                                          SmartWalletBehaviorProfileMapper behaviorProfileMapper,
                                          SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper,
                                          SmartWalletManualAllowlistMapper allowlistMapper,
                                          SmartWalletManualBlocklistMapper blocklistMapper) {
        this.profileMapper = profileMapper;
        this.behaviorProfileMapper = behaviorProfileMapper;
        this.walletFeatureDailyMapper = walletFeatureDailyMapper;
        this.allowlistMapper = allowlistMapper;
        this.blocklistMapper = blocklistMapper;
    }

    // ════════════════════════════════════════════════════════════════
    // Profiles
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletProfileEntity> profiles() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletProfileEntity::getUpdatedAt));
    }

    public CursorPageView<WalletScreenerView> screener(String scope,
                                                       Integer limit,
                                                       String cursor,
                                                       String sortBy,
                                                       String sortDirection,
                                                       String screenerTier,
                                                       Boolean highQualityAddress,
                                                       Boolean highFrequencyTradingAddress,
                                                       String winSourceType,
                                                       String recommendedExitLane,
                                                       BigDecimal minQualityScore,
                                                       BigDecimal minReplicabilityScore) {
        List<SmartWalletProfileEntity> profiles = scopeProfiles(scope);
        if (profiles.isEmpty()) {
            return CursorPageView.empty();
        }
        Map<String, SmartWalletBehaviorProfileEntity> behaviorByWallet = behaviorProfileMapper.selectList(new LambdaQueryWrapper<SmartWalletBehaviorProfileEntity>()
                        .eq(SmartWalletBehaviorProfileEntity::getSource, "POLYMARKET"))
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.hasText(item.getWalletAddress()))
                .collect(Collectors.toMap(item -> item.getWalletAddress().trim().toLowerCase(), Function.identity(), (a, b) -> a));
        Map<String, SmartWalletWalletFeatureDailyEntity> featureByWallet = walletFeatureDailyMapper.selectList(new LambdaQueryWrapper<SmartWalletWalletFeatureDailyEntity>()
                        .orderByDesc(SmartWalletWalletFeatureDailyEntity::getFeatureDate))
                .stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.hasText(item.getWalletAddress()))
                .collect(Collectors.toMap(item -> item.getWalletAddress().trim().toLowerCase(), Function.identity(), (a, b) -> a));

        List<WalletScreenerView> views = profiles.stream()
                .map(profile -> toScreenerView(profile,
                        behaviorByWallet.get(profile.getWalletAddress().trim().toLowerCase()),
                        featureByWallet.get(profile.getWalletAddress().trim().toLowerCase())))
                .filter(Objects::nonNull)
                .filter(view -> screenerTier == null || normalize(view.screenerTier()).equals(normalize(screenerTier)))
                .filter(view -> highQualityAddress == null || highQualityAddress.equals(Boolean.TRUE.equals(view.highQualityAddress())))
                .filter(view -> highFrequencyTradingAddress == null || highFrequencyTradingAddress.equals(Boolean.TRUE.equals(view.highFrequencyTradingAddress())))
                .filter(view -> winSourceType == null || normalize(view.winSourceType()).equals(normalize(winSourceType)))
                .filter(view -> recommendedExitLane == null || normalize(view.recommendedExitLane()).equals(normalize(recommendedExitLane)))
                .filter(view -> minQualityScore == null || safe(view.qualityScore()).compareTo(minQualityScore) >= 0)
                .filter(view -> minReplicabilityScore == null || safe(view.replicabilityScore()).compareTo(minReplicabilityScore) >= 0)
                .sorted(screenerComparator(sortBy, sortDirection))
                .toList();
        int safeLimit = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        int start = parseCursor(cursor);
        if (start >= views.size()) {
            return CursorPageView.empty();
        }
        int end = Math.min(views.size(), start + safeLimit);
        String nextCursor = end < views.size() ? String.valueOf(end) : null;
        return CursorPageView.of(views.subList(start, end), nextCursor);
    }

    // ════════════════════════════════════════════════════════════════
    // Profile Overrides
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletProfileOverrideView> listManualOverrides(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE)
                        .and(wrapper -> wrapper
                                .isNotNull(SmartWalletProfileEntity::getManualWatchEnabled)
                                .or()
                                .isNotNull(SmartWalletProfileEntity::getManualRecommendedExitLane)
                                .or()
                                .isNotNull(SmartWalletProfileEntity::getManualOverrideNote))
                        .orderByDesc(SmartWalletProfileEntity::getManualOverrideUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toOverrideView)
                .toList();
    }

    public SmartWalletProfileOverrideSummaryView summarizeManualOverrides() {
        List<SmartWalletProfileEntity> rows = profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .and(wrapper -> wrapper
                        .isNotNull(SmartWalletProfileEntity::getManualWatchEnabled)
                        .or()
                        .isNotNull(SmartWalletProfileEntity::getManualRecommendedExitLane)
                        .or()
                        .isNotNull(SmartWalletProfileEntity::getManualOverrideNote)));

        int manualWatchEnabled = 0, manualWatchDisabled = 0, exitLaneSet = 0, recentlyUpdated = 0;
        long recentCutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        for (SmartWalletProfileEntity row : rows) {
            if (Boolean.TRUE.equals(row.getManualWatchEnabled())) manualWatchEnabled++;
            if (Boolean.FALSE.equals(row.getManualWatchEnabled())) manualWatchDisabled++;
            if (StringUtils.hasText(row.getManualRecommendedExitLane())) exitLaneSet++;
            if (row.getManualOverrideUpdatedAt() != null && row.getManualOverrideUpdatedAt().getTime() >= recentCutoff) {
                recentlyUpdated++;
            }
        }
        return new SmartWalletProfileOverrideSummaryView(rows.size(), manualWatchEnabled, manualWatchDisabled,
                exitLaneSet, recentlyUpdated);
    }

    // ════════════════════════════════════════════════════════════════
    // Manual Allowlist
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletManualAllowlistView> allowlist(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return allowlistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                        .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletManualAllowlistEntity::getUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toAllowlistView)
                .toList();
    }

    public SmartWalletManualAllowlistSummaryView allowlistSummary() {
        List<SmartWalletManualAllowlistEntity> rows = allowlistMapper.selectList(
                new LambdaQueryWrapper<SmartWalletManualAllowlistEntity>()
                        .eq(SmartWalletManualAllowlistEntity::getSource, SOURCE));
        int active = 0, expired = 0, disabled = 0, paperOnly = 0, liveEnabled = 0;
        Date now = new Date();
        for (SmartWalletManualAllowlistEntity row : rows) {
            if (isAllowlistActive(row)) active++;
            if (row.getExpiresAt() != null && !row.getExpiresAt().after(now)) expired++;
            if (STATUS_DISABLED.equalsIgnoreCase(row.getStatus())) disabled++;
            String mode = normalizeAllowlistMode(row.getAllowedExecutionModes());
            if ("PAPER".equals(mode)) paperOnly++;
            if ("LIVE".equals(mode) || "BOTH".equals(mode)) liveEnabled++;
        }
        return new SmartWalletManualAllowlistSummaryView(rows.size(), active, expired, disabled, paperOnly, liveEnabled);
    }

    // ════════════════════════════════════════════════════════════════
    // Manual Blocklist
    // ════════════════════════════════════════════════════════════════

    public List<SmartWalletManualBlocklistView> blocklist(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return blocklistMapper.selectList(new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                        .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE)
                        .orderByDesc(SmartWalletManualBlocklistEntity::getUpdatedAt)
                        .last("limit " + safeLimit))
                .stream()
                .map(this::toBlocklistView)
                .toList();
    }

    public SmartWalletManualBlocklistSummaryView blocklistSummary() {
        List<SmartWalletManualBlocklistEntity> rows = blocklistMapper.selectList(
                new LambdaQueryWrapper<SmartWalletManualBlocklistEntity>()
                        .eq(SmartWalletManualBlocklistEntity::getSource, SOURCE));
        int active = 0, disabled = 0;
        for (SmartWalletManualBlocklistEntity row : rows) {
            if (isBlocklistActive(row)) active++;
            if (STATUS_DISABLED.equalsIgnoreCase(row.getStatus())) disabled++;
        }
        return new SmartWalletManualBlocklistSummaryView(rows.size(), active, disabled);
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private SmartWalletProfileOverrideView toOverrideView(SmartWalletProfileEntity p) {
        return new SmartWalletProfileOverrideView(p.getWalletAddress(), p.getAlias(), p.getQualified(),
                p.getWatchEnabled(), p.getManualWatchEnabled(), p.getManualRecommendedExitLane(),
                p.getManualOverrideNote(), p.getManualOverrideUpdatedAt());
    }

    private SmartWalletManualAllowlistView toAllowlistView(SmartWalletManualAllowlistEntity e) {
        return new SmartWalletManualAllowlistView(e.getId(), e.getWalletAddress(), e.getSource(), e.getStatus(),
                e.getAllowedExecutionModes(), e.getStakeMultiplier(), e.getMaxLiveOrdersPerDay(),
                e.getMaxLiveOrdersPerRun(), e.getExpiresAt(), e.getReason(), e.getCreatedBy(),
                isAllowlistActive(e), e.getCreatedAt(), e.getUpdatedAt());
    }

    private SmartWalletManualBlocklistView toBlocklistView(SmartWalletManualBlocklistEntity e) {
        return new SmartWalletManualBlocklistView(e.getId(), e.getWalletAddress(), e.getSource(), e.getStatus(),
                e.getReason(), e.getCreatedBy(), isBlocklistActive(e), e.getCreatedAt(), e.getUpdatedAt());
    }

    private boolean isAllowlistActive(SmartWalletManualAllowlistEntity e) {
        if (e == null || !STATUS_ACTIVE.equalsIgnoreCase(e.getStatus())) return false;
        return e.getExpiresAt() == null || e.getExpiresAt().after(new Date());
    }

    private boolean isBlocklistActive(SmartWalletManualBlocklistEntity e) {
        return e != null && STATUS_ACTIVE.equalsIgnoreCase(e.getStatus());
    }

    private List<SmartWalletProfileEntity> scopeProfiles(String scope) {
        LambdaQueryWrapper<SmartWalletProfileEntity> wrapper = new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletProfileEntity::getUpdatedAt);
        if ("all".equalsIgnoreCase(scope)) {
            return profileMapper.selectList(wrapper);
        }
        if ("warm".equalsIgnoreCase(scope)) {
            return profileMapper.selectList(wrapper.eq(SmartWalletProfileEntity::getMonitorTier, "WARM"));
        }
        if ("executable".equalsIgnoreCase(scope)) {
            return profileMapper.selectList(wrapper.eq(SmartWalletProfileEntity::getMonitorTier, "HOT"));
        }
        return profileMapper.selectList(wrapper.eq(SmartWalletProfileEntity::getQualified, true));
    }

    private WalletScreenerView toScreenerView(SmartWalletProfileEntity profile,
                                              SmartWalletBehaviorProfileEntity behavior,
                                              SmartWalletWalletFeatureDailyEntity feature) {
        if (profile == null || !StringUtils.hasText(profile.getWalletAddress())) {
            return null;
        }
        BigDecimal recentWinRate30d = firstNonNull(profile.getRecentWinRate30d(), profile.getWinRate());
        BigDecimal recentWinRate7d = profile.getRecentWinRate7d();
        BigDecimal freshnessScore = freshnessScore(profile.getLastTradeAt());
        boolean behaviorStale = behavior == null || behavior.getComputedAt() == null
                || System.currentTimeMillis() - behavior.getComputedAt().getTime() > BEHAVIOR_PROFILE_STALE_MS;
        boolean fallbackUsed = behavior == null;
        BigDecimal qualityScore = behavior == null ? BigDecimal.ZERO : safe(behavior.getQualityScore());
        BigDecimal replicabilityScore = behavior == null ? BigDecimal.ZERO : safe(behavior.getReplicabilityScore());
        boolean highQuality = safe(recentWinRate30d).compareTo(new BigDecimal("0.58")) >= 0
                && (behavior == null ? 0 : defaultInt(behavior.getClosedPositionSampleSize())) >= 20
                && safe(firstNonNull(feature == null ? null : feature.getPnl30d(), profile.getRealizedPnlUsdc())).compareTo(BigDecimal.ZERO) > 0
                && freshnessScore.compareTo(new BigDecimal("0.85")) >= 0
                && safe(profile.getMaxDrawdownRate()).compareTo(new BigDecimal("0.35")) <= 0;
        String tier = determineTier(profile, behavior, freshnessScore, highQuality);
        return new WalletScreenerView(
                profile.getWalletAddress(),
                profile.getAlias(),
                behavior == null ? null : behavior.getArchetype(),
                behavior == null ? null : behavior.getFollowLane(),
                behavior == null ? null : behavior.getSummary(),
                null,
                tier,
                highQuality,
                behavior == null ? null : behavior.getHighFrequencyTradingAddress(),
                behavior == null ? null : behavior.getWinSourceType(),
                scale(behavior == null ? null : behavior.getQualityScore(), 4),
                scale(behavior == null ? null : behavior.getReplicabilityScore(), 4),
                scale(behavior == null ? null : behavior.getStabilityScore(), 4),
                recentWinRate7d,
                scale(recentWinRate30d, 4),
                behavior == null ? null : behavior.getTrades7d(),
                behavior == null ? null : behavior.getTrades30d(),
                scale(behavior == null ? null : behavior.getMedianHoldMinutes(), 2),
                dataQuality(behavior),
                scale(behavior == null ? null : behavior.getOrderWinRate(), 4),
                scale(behavior == null ? null : behavior.getAverageOrderRoiRate(), 4),
                scale(behavior == null ? null : behavior.getMedianOrderRoiRate(), 4),
                scale(behavior == null ? null : behavior.getSettlementWinRate(), 4),
                scale(feature == null ? null : feature.getFollowableRate30d(), 4),
                scale(feature == null ? null : feature.getConcentrationMarket30d(), 4),
                scale(freshnessScore, 4),
                profile.getMonitorTier(),
                profile.getMonitorReason(),
                behavior == null ? null : behavior.getRecommendedExitLane(),
                profile.getLastTradeAt(),
                behavior == null ? null : behavior.getSummary(),
                behavior == null ? null : behavior.getComputedAt(),
                behaviorStale,
                fallbackUsed
        );
    }

    private String determineTier(SmartWalletProfileEntity profile,
                                 SmartWalletBehaviorProfileEntity behavior,
                                 BigDecimal freshnessScore,
                                 boolean highQuality) {
        BigDecimal qualityScore = behavior == null ? BigDecimal.ZERO : safe(behavior.getQualityScore());
        BigDecimal replicabilityScore = behavior == null ? BigDecimal.ZERO : safe(behavior.getReplicabilityScore());
        if (highQuality
                && qualityScore.compareTo(new BigDecimal("0.72")) >= 0
                && replicabilityScore.compareTo(new BigDecimal("0.60")) >= 0
                && !"FAST_EXIT_OR_SKIP".equalsIgnoreCase(safeString(behavior == null ? null : behavior.getRecommendedExitLane()))) {
            return "EXECUTABLE_A";
        }
        if (qualityScore.compareTo(new BigDecimal("0.60")) >= 0
                && replicabilityScore.compareTo(new BigDecimal("0.45")) >= 0
                && freshnessScore.compareTo(new BigDecimal("0.50")) >= 0) {
            return "EXECUTABLE_B";
        }
        return "OBSERVE";
    }

    private Comparator<WalletScreenerView> screenerComparator(String sortBy, String sortDirection) {
        Comparator<WalletScreenerView> comparator = switch (normalize(sortBy)) {
            case "RECENTWINRATE30D" -> Comparator.comparing(WalletScreenerView::recentWinRate30d, Comparator.nullsLast(Comparator.naturalOrder()));
            case "ORDERWINRATE" -> Comparator.comparing(WalletScreenerView::orderWinRate, Comparator.nullsLast(Comparator.naturalOrder()));
            case "LASTTRADEAT" -> Comparator.comparing(WalletScreenerView::lastTradeAt, Comparator.nullsLast(Comparator.naturalOrder()));
            case "QUALITYSCORE" -> Comparator.comparing(WalletScreenerView::qualityScore, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(WalletScreenerView::replicabilityScore, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (!"ASC".equalsIgnoreCase(sortDirection)) {
            comparator = comparator.reversed();
        }
        return comparator.thenComparing(WalletScreenerView::walletAddress, Comparator.nullsLast(String::compareTo));
    }

    private String dataQuality(SmartWalletBehaviorProfileEntity behavior) {
        int sampleSize = behavior == null ? 0 : defaultInt(behavior.getClosedPositionSampleSize());
        if (sampleSize >= 20) {
            return "HIGH";
        }
        if (sampleSize >= 3) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private BigDecimal freshnessScore(Date lastTradeAt) {
        if (lastTradeAt == null) {
            return BigDecimal.ZERO;
        }
        long ageMs = Math.max(0L, System.currentTimeMillis() - lastTradeAt.getTime());
        long ageDays = ageMs / (24L * 3600_000L);
        if (ageDays <= 7) return BigDecimal.ONE;
        if (ageDays <= 14) return new BigDecimal("0.85");
        if (ageDays <= 30) return new BigDecimal("0.60");
        if (ageDays <= 90) return new BigDecimal("0.25");
        return BigDecimal.ZERO;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().replace("_", "").toUpperCase(Locale.ROOT) : null;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal firstNonNull(BigDecimal primary, BigDecimal secondary) {
        return primary != null ? primary : secondary;
    }

    private int parseCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizeAllowlistMode(String mode) {
        if (!StringUtils.hasText(mode)) return "PAPER";
        return mode.trim().toUpperCase();
    }
}
