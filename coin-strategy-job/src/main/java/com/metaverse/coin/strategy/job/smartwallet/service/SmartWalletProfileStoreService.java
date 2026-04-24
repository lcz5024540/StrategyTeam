package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileBatchOverrideResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileOverrideView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletProfileStoreService {

    private static final String SOURCE = "POLYMARKET";
    // Days thresholds for activity decay (P2)
    private static final long DECAY_START_DAYS   = 30L;
    private static final long OFFLINE_DAYS        = 90L;
    // Score penalty per week of inactivity after DECAY_START_DAYS
    private static final BigDecimal DECAY_PER_WEEK = new BigDecimal("0.05");
    private static final int MIN_ARCHETYPE_EVIDENCE_SAMPLE = 5;
    private static final BigDecimal MIN_EXECUTABLE_FOLLOW_FIT_SCORE = new BigDecimal("0.42");

    private final SmartWalletProperties properties;
    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final WalletArchetypeClassifierService walletArchetypeClassifierService;
    private final WalletBehaviorProfileService walletBehaviorProfileService;

    public SmartWalletProfileEntity upsertMetrics(PolymarketWalletMetricsRequest request) {
        SmartWalletProfileEntity entity = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, request.getWalletAddress())
                .eq(SmartWalletProfileEntity::getChain, request.getChain())
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));

        if (entity == null) {
            entity = new SmartWalletProfileEntity()
                    .setWalletAddress(request.getWalletAddress())
                    .setChain(request.getChain())
                    .setSource(SOURCE)
                    .setCreatedAt(new Date());
            entity.setQualified(Boolean.FALSE);
            entity.setWatchEnabled(Boolean.FALSE);
        }
        Date previousDiscoveredAt = entity.getLastDiscoveredAt();

        entity.setAlias(request.getAlias())
                .setWinRate(safe(request.getWinRate()))
                .setRealizedPnlUsdc(safe(request.getRealizedPnlUsdc()))
                .setTotalTrades(request.getTotalTrades() == null ? 0 : request.getTotalTrades())
                .setAverageSizeUsdc(safe(request.getAverageSizeUsdc()))
                .setAverageRoiRate(safe(request.getAverageRoiRate()))
                .setMaxDrawdownRate(safe(request.getMaxDrawdownRate()))
                .setLastTradeAt(request.getLastTradeAt())
                .setWalletScore(scoringService.score(
                        safe(request.getWinRate()),
                        firstNonNull(request.getRecentWinRate30d(), entity.getRecentWinRate30d()),
                        safe(request.getRealizedPnlUsdc()),
                        request.getTotalTrades() == null ? 0 : request.getTotalTrades(),
                        safe(request.getAverageRoiRate()),
                        safe(request.getMaxDrawdownRate())
                ))
                .setUpdatedAt(new Date());

        if (request.getRecentWinRate30d() != null) {
            entity.setRecentWinRate30d(safe(request.getRecentWinRate30d()));
        }
        if (request.getLastDiscoveredAt() != null) {
            entity.setLastDiscoveredAt(request.getLastDiscoveredAt());
            entity.setLastDiscoveryQuery(request.getLastDiscoveryQuery());
            entity.setLeaderboardHitCount30d(nextLeaderboardHitCount(previousDiscoveredAt, entity.getLeaderboardHitCount30d(), request));
            entity.setDominantSession(request.getDominantSession());
            entity.setAsiaTradeRatio30d(safe(request.getAsiaTradeRatio30d()));
            entity.setEuTradeRatio30d(safe(request.getEuTradeRatio30d()));
            entity.setUsTradeRatio30d(safe(request.getUsTradeRatio30d()));
            entity.setActiveHourHistogramJson(request.getActiveHourHistogramJson());
        }

        if (entity.getId() == null) {
            profileMapper.insert(entity);
        } else {
            profileMapper.updateById(entity);
        }
        walletBehaviorProfileService.evictProfileCache(entity.getWalletAddress());
        return entity;
    }

    public List<SmartWalletProfileEntity> listAll() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                .orderByDesc(SmartWalletProfileEntity::getRealizedPnlUsdc));
    }

    public List<SmartWalletProfileEntity> listWatchEnabled() {
        return listExecutableWatchlist();
    }

    public List<SmartWalletProfileEntity> listExecutableWatchlist() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .eq(SmartWalletProfileEntity::getQualified, Boolean.TRUE)
                .eq(SmartWalletProfileEntity::getWatchEnabled, Boolean.TRUE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore));
    }

    public List<SmartWalletProfileEntity> listQualified() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .eq(SmartWalletProfileEntity::getQualified, Boolean.TRUE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore));
    }

    public List<SmartWalletProfileEntity> listRecentlyDiscovered(Date since, int limit) {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .ge(SmartWalletProfileEntity::getLastDiscoveredAt, since)
                .isNotNull(SmartWalletProfileEntity::getLastDiscoveredAt)
                .orderByDesc(SmartWalletProfileEntity::getLastDiscoveredAt)
                .last("limit " + Math.max(limit, 1)));
    }

    public void update(SmartWalletProfileEntity entity) {
        entity.setUpdatedAt(new Date());
        profileMapper.updateById(entity);
        walletBehaviorProfileService.evictProfileCache(entity.getWalletAddress());
    }

    public List<SmartWalletProfileOverrideView> listManualOverrides(Integer limit) {
        int safeLimit = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
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

        int observeOnlyOverrides = 0;
        int forceExecutableOverrides = 0;
        int exitLaneOverrides = 0;
        int noteOnlyOverrides = 0;
        int updatedLast24h = 0;
        long recentCutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        LinkedHashMap<String, Integer> exitLaneCounts = new LinkedHashMap<>();
        for (SmartWalletProfileEntity row : rows) {
            boolean hasManualWatch = row.getManualWatchEnabled() != null;
            boolean hasExitLane = StringUtils.hasText(row.getManualRecommendedExitLane());
            boolean hasNote = StringUtils.hasText(row.getManualOverrideNote());
            if (Boolean.FALSE.equals(row.getManualWatchEnabled())) {
                observeOnlyOverrides++;
            }
            if (Boolean.TRUE.equals(row.getManualWatchEnabled())) {
                forceExecutableOverrides++;
            }
            if (hasExitLane) {
                exitLaneOverrides++;
                String lane = row.getManualRecommendedExitLane().trim().toUpperCase(Locale.ROOT);
                exitLaneCounts.merge(lane, 1, Integer::sum);
            }
            if (!hasManualWatch && !hasExitLane && hasNote) {
                noteOnlyOverrides++;
            }
            if (row.getManualOverrideUpdatedAt() != null && row.getManualOverrideUpdatedAt().getTime() >= recentCutoff) {
                updatedLast24h++;
            }
        }
        return SmartWalletProfileOverrideSummaryView.builder()
                .totalOverrides(rows.size())
                .observeOnlyOverrides(observeOnlyOverrides)
                .forceExecutableOverrides(forceExecutableOverrides)
                .exitLaneOverrides(exitLaneOverrides)
                .noteOnlyOverrides(noteOnlyOverrides)
                .updatedLast24h(updatedLast24h)
                .exitLaneCounts(exitLaneCounts)
                .build();
    }

    public SmartWalletProfileEntity updateManualOverride(String walletAddress, SmartWalletProfileOverrideRequest request) {
        if (!org.springframework.util.StringUtils.hasText(walletAddress)) {
            throw new IllegalArgumentException("walletAddress is required");
        }
        SmartWalletProfileEntity entity = profileMapper.selectOne(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .last("limit 1"));
        if (entity == null) {
            throw new IllegalArgumentException("Smart-wallet profile not found: " + walletAddress);
        }
        Date now = new Date();
        if (request != null) {
            if (Boolean.TRUE.equals(request.getClearManualWatchEnabled())) {
                entity.setManualWatchEnabled(null);
            } else if (request.getManualWatchEnabled() != null) {
                entity.setManualWatchEnabled(request.getManualWatchEnabled());
            }

            if (Boolean.TRUE.equals(request.getClearManualRecommendedExitLane())) {
                entity.setManualRecommendedExitLane(null);
            } else if (org.springframework.util.StringUtils.hasText(request.getManualRecommendedExitLane())) {
                entity.setManualRecommendedExitLane(request.getManualRecommendedExitLane().trim());
            }

            if (request.getNote() != null) {
                entity.setManualOverrideNote(request.getNote().trim().isEmpty() ? null : request.getNote().trim());
            }
        }
        entity.setManualOverrideUpdatedAt(now);
        applyQualificationState(entity);
        entity.setUpdatedAt(now);
        profileMapper.updateById(entity);
        walletBehaviorProfileService.evictProfileCache(entity.getWalletAddress());
        return entity;
    }

    public SmartWalletProfileBatchOverrideResultView batchUpdateManualOverrides(SmartWalletProfileBatchOverrideRequest request) {
        if (request == null || request.getWalletAddresses() == null || request.getWalletAddresses().isEmpty()) {
            throw new IllegalArgumentException("walletAddresses is required");
        }
        List<String> uniqueWalletAddresses = request.getWalletAddresses().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (uniqueWalletAddresses.isEmpty()) {
            throw new IllegalArgumentException("walletAddresses is required");
        }

        SmartWalletProfileOverrideRequest singleRequest = request.toSingleOverrideRequest();
        List<String> updatedWallets = new ArrayList<>();
        List<String> missingWallets = new ArrayList<>();
        for (String walletAddress : uniqueWalletAddresses) {
            try {
                updateManualOverride(walletAddress, singleRequest);
                updatedWallets.add(walletAddress);
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage() != null && ex.getMessage().startsWith("Smart-wallet profile not found")) {
                    missingWallets.add(walletAddress);
                    continue;
                }
                throw ex;
            }
        }
        return SmartWalletProfileBatchOverrideResultView.builder()
                .requestedCount(request.getWalletAddresses().size())
                .uniqueWalletCount(uniqueWalletAddresses.size())
                .updatedCount(updatedWallets.size())
                .missingCount(missingWallets.size())
                .updatedWalletAddresses(updatedWallets)
                .missingWalletAddresses(missingWallets)
                .build();
    }

    // -------------------------------------------------------------------------
    // P2: Activity-decay job — runs every 6 hours.
    // Wallets inactive >30 days lose walletScore by DECAY_PER_WEEK per week.
    // Wallets inactive >90 days are disabled (watchEnabled=false).
    // -------------------------------------------------------------------------
    @Scheduled(fixedDelay = 6 * 3600_000L)
    public void applyActivityDecay() {
        List<SmartWalletProfileEntity> wallets = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE));
        Date now = new Date();
        for (SmartWalletProfileEntity wallet : wallets) {
            Date lastTrade = wallet.getLastTradeAt();
            if (lastTrade == null) {
                continue;
            }
            long daysSince = TimeUnit.MILLISECONDS.toDays(now.getTime() - lastTrade.getTime());
            if (daysSince <= DECAY_START_DAYS) {
                continue;
            }
            if (daysSince >= OFFLINE_DAYS && Boolean.TRUE.equals(wallet.getWatchEnabled())) {
                wallet.setWatchEnabled(Boolean.FALSE);
                wallet.setUpdatedAt(now);
                profileMapper.updateById(wallet);
                log.info("WALLET_DECAY offline walletAddress={} daysSinceLastTrade={}", wallet.getWalletAddress(), daysSince);
                continue;
            }
            // Decay: 5% per week beyond the 30-day grace period
            long weeksOverThreshold = (daysSince - DECAY_START_DAYS) / 7 + 1;
            BigDecimal penalty = DECAY_PER_WEEK.multiply(BigDecimal.valueOf(weeksOverThreshold));
            BigDecimal decayedScore = safe(wallet.getWalletScore()).subtract(penalty).max(BigDecimal.ZERO)
                    .setScale(4, RoundingMode.HALF_UP);
            if (decayedScore.compareTo(safe(wallet.getWalletScore())) < 0) {
                wallet.setWalletScore(decayedScore);
                applyQualificationState(wallet);
                wallet.setUpdatedAt(now);
                profileMapper.updateById(wallet);
                log.debug("WALLET_DECAY score walletAddress={} daysSince={} newScore={}", wallet.getWalletAddress(), daysSince, decayedScore);
            }
        }
    }

    // -------------------------------------------------------------------------
    // P2: Internal win-rate refresh — runs every 15 minutes by default.
    // Computes real win rate from our own closed positions for each wallet and
    // blends it with the external leaderboard win rate (weight 0.4 internal / 0.6 external)
    // to correct for survivorship bias in the official leaderboard data.
    //
    // Survivorship-bias fallback: when a wallet has winRate=1.0 with a small sample
    // (totalTrades <= LEADERBOARD_POSITION_CAP) and we have no internal closed positions yet,
    // cap the win rate to LEADERBOARD_BIAS_FALLBACK_WIN_RATE immediately rather than waiting
    // for internal data to accumulate. This prevents wallets with inflated leaderboard metrics
    // from qualifying for single-wallet override from the very first signal.
    // -------------------------------------------------------------------------
    private static final int    LEADERBOARD_POSITION_CAP          = 50;
    private static final BigDecimal LEADERBOARD_BIAS_WIN_RATE_TRIGGER = new BigDecimal("1.0");
    private static final BigDecimal LEADERBOARD_BIAS_FALLBACK_WIN_RATE = new BigDecimal("0.65");

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.internal-win-rate-refresh-fixed-delay-ms:900000}")
    public void refreshInternalWinRate() {
        List<SmartWalletProfileEntity> wallets = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE));

        for (SmartWalletProfileEntity wallet : wallets) {
            List<SmartWalletFollowTaskEntity> followedTasks = followTaskMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                            .eq(SmartWalletFollowTaskEntity::getWalletAddress, wallet.getWalletAddress())
                            .eq(SmartWalletFollowTaskEntity::getStatus, "FOLLOWED"));

            int total = 0;
            int wins = 0;
            for (SmartWalletPositionEntity pos : resolveClosedPositionsForWallet(followedTasks)) {
                total++;
                if (safe(pos.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) > 0) {
                    wins++;
                }
            }

            BigDecimal currentWinRate = safe(wallet.getWinRate());
            int leaderboardTrades = wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades();

            if (total < 3) {
                // Not enough internal data for blending yet.
                // Survivorship-bias fallback: wallets with winRate=1.0 and small leaderboard samples
                // almost certainly have inflated metrics — cap them to a conservative default immediately.
                boolean suspiciouslyPerfect = currentWinRate.compareTo(LEADERBOARD_BIAS_WIN_RATE_TRIGGER) >= 0
                        && leaderboardTrades <= LEADERBOARD_POSITION_CAP;
                if (suspiciouslyPerfect && currentWinRate.compareTo(LEADERBOARD_BIAS_FALLBACK_WIN_RATE) > 0) {
                    BigDecimal fallbackScore = scoringService.score(
                            LEADERBOARD_BIAS_FALLBACK_WIN_RATE,
                            wallet.getRecentWinRate30d(),
                            safe(wallet.getRealizedPnlUsdc()),
                            leaderboardTrades,
                            safe(wallet.getAverageRoiRate()),
                            safe(wallet.getMaxDrawdownRate())
                    );
                    wallet.setWinRate(LEADERBOARD_BIAS_FALLBACK_WIN_RATE);
                    wallet.setWalletScore(fallbackScore);
                    applyQualificationState(wallet);
                    wallet.setUpdatedAt(new Date());
                    profileMapper.updateById(wallet);
                    log.info("WIN_RATE_BIAS_FALLBACK walletAddress={} leaderboardTrades={} winRate reset {} -> {} newScore={}",
                            wallet.getWalletAddress(), leaderboardTrades, currentWinRate,
                            LEADERBOARD_BIAS_FALLBACK_WIN_RATE, fallbackScore);
                }
                continue;
            }

            BigDecimal internalWinRate = BigDecimal.valueOf(wins)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            // Blend: 40% internal (truth) + 60% external (leaderboard) to smooth noise
            BigDecimal blendedWinRate = internalWinRate.multiply(new BigDecimal("0.40"))
                    .add(currentWinRate.multiply(new BigDecimal("0.60")))
                    .setScale(4, RoundingMode.HALF_UP);

            // Re-score wallet with blended win rate
            BigDecimal newScore = scoringService.score(
                    blendedWinRate,
                    wallet.getRecentWinRate30d(),
                    safe(wallet.getRealizedPnlUsdc()),
                    leaderboardTrades,
                    safe(wallet.getAverageRoiRate()),
                    safe(wallet.getMaxDrawdownRate())
            );
            wallet.setWinRate(blendedWinRate);
            wallet.setWalletScore(newScore);
            applyQualificationState(wallet);
            wallet.setUpdatedAt(new Date());
            profileMapper.updateById(wallet);
            log.info("WIN_RATE_REFRESH walletAddress={} internalWinRate={} blended={} newScore={} sampleSize={}",
                    wallet.getWalletAddress(), internalWinRate, blendedWinRate, newScore, total);
        }
    }

    private List<SmartWalletPositionEntity> resolveClosedPositionsForWallet(List<SmartWalletFollowTaskEntity> followedTasks) {
        if (followedTasks == null || followedTasks.isEmpty()) {
            return List.of();
        }
        List<Long> followTaskIds = followedTasks.stream()
                .map(SmartWalletFollowTaskEntity::getId)
                .filter(Objects::nonNull)
                .toList();
        if (followTaskIds.isEmpty()) {
            return List.of();
        }

        List<SmartWalletOrderEntity> relatedOrders = orderMapper.selectList(
                new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .in(SmartWalletOrderEntity::getFollowTaskId, followTaskIds)
                        .isNotNull(SmartWalletOrderEntity::getPositionId));
        Set<Long> positionIds = relatedOrders.stream()
                .map(SmartWalletOrderEntity::getPositionId)
                .filter(Objects::nonNull)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (positionIds.isEmpty()) {
            return List.of();
        }

        List<SmartWalletPositionEntity> positions = new ArrayList<>(positionMapper.selectBatchIds(positionIds));
        return positions.stream()
                .filter(Objects::nonNull)
                .filter(position -> List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED").contains(
                        position.getStatus() == null ? "" : position.getStatus().toUpperCase()))
                .toList();
    }

    public void applyQualificationState(SmartWalletProfileEntity wallet) {
        boolean qualified = isQualified(wallet);
        wallet.setQualified(qualified);
        boolean executableCandidate = qualified && isExecutableCandidate(wallet);
        if (wallet.getManualWatchEnabled() != null) {
            wallet.setWatchEnabled(qualified && wallet.getManualWatchEnabled());
            return;
        }
        wallet.setWatchEnabled(executableCandidate);
    }

    private boolean isQualified(SmartWalletProfileEntity wallet) {
        return safe(wallet.getWinRate()).compareTo(safe(properties.getMinWinRate())) >= 0
                && safe(wallet.getWalletScore()).compareTo(safe(properties.getMinWalletScore())) >= 0
                && (wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades()) >= defaultInt(properties.getMinTotalTrades())
                && safe(wallet.getRealizedPnlUsdc()).compareTo(safe(properties.getMinRealizedPnlUsdc())) >= 0
                && safe(wallet.getMaxDrawdownRate()).compareTo(safe(properties.getMaxDrawdownRate())) <= 0;
    }

    private boolean isExecutableCandidate(SmartWalletProfileEntity wallet) {
        if (wallet == null || !Boolean.TRUE.equals(wallet.getQualified())) {
            return false;
        }
        WalletBehaviorProfileView behaviorProfile = walletBehaviorProfileService.getProfile(wallet.getWalletAddress());
        if (behaviorProfile != null
                && StringUtils.hasText(behaviorProfile.recommendedExitLane())
                && "FAST_EXIT_OR_SKIP".equalsIgnoreCase(behaviorProfile.recommendedExitLane())) {
            return false;
        }
        WalletArchetypeView archetype = walletArchetypeClassifierService.classify(wallet.getWalletAddress(), wallet);
        if (archetype == null) {
            return true;
        }
        if (archetype.labelSampleSize() < MIN_ARCHETYPE_EVIDENCE_SAMPLE) {
            return true;
        }
        if ("OBSERVE_ONLY".equalsIgnoreCase(archetype.followLane())) {
            return false;
        }
        return safe(archetype.followFitScore()).compareTo(MIN_EXECUTABLE_FOLLOW_FIT_SCORE) >= 0;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private BigDecimal firstNonNull(BigDecimal primary, BigDecimal fallback) {
        return primary != null ? primary : fallback;
    }

    private SmartWalletProfileOverrideView toOverrideView(SmartWalletProfileEntity entity) {
        return SmartWalletProfileOverrideView.builder()
                .walletAddress(entity.getWalletAddress())
                .alias(entity.getAlias())
                .qualified(entity.getQualified())
                .watchEnabled(entity.getWatchEnabled())
                .manualWatchEnabled(entity.getManualWatchEnabled())
                .manualRecommendedExitLane(entity.getManualRecommendedExitLane())
                .manualOverrideNote(entity.getManualOverrideNote())
                .manualOverrideUpdatedAt(entity.getManualOverrideUpdatedAt())
                .build();
    }

    private int nextLeaderboardHitCount(Date previousDiscoveredAt, Integer currentHitCount, PolymarketWalletMetricsRequest request) {
        int increment = request.getLeaderboardHitCount30d() == null ? 1 : Math.max(request.getLeaderboardHitCount30d(), 1);
        if (previousDiscoveredAt == null) {
            return increment;
        }
        long elapsedMs = request.getLastDiscoveredAt().getTime() - previousDiscoveredAt.getTime();
        if (elapsedMs > TimeUnit.DAYS.toMillis(30)) {
            return increment;
        }
        return (currentHitCount == null ? 0 : currentHitCount) + increment;
    }
}
