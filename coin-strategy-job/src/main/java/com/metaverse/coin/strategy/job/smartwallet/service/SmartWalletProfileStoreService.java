package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
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

    private final SmartWalletProfileMapper profileMapper;
    private final SmartWalletScoringService scoringService;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final SmartWalletPositionMapper positionMapper;

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
                        safe(request.getRealizedPnlUsdc()),
                        request.getTotalTrades() == null ? 0 : request.getTotalTrades(),
                        safe(request.getAverageRoiRate()),
                        safe(request.getMaxDrawdownRate())
                ))
                .setUpdatedAt(new Date());

        if (entity.getId() == null) {
            profileMapper.insert(entity);
        } else {
            profileMapper.updateById(entity);
        }
        return entity;
    }

    public List<SmartWalletProfileEntity> listAll() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore)
                .orderByDesc(SmartWalletProfileEntity::getRealizedPnlUsdc));
    }

    public List<SmartWalletProfileEntity> listWatchEnabled() {
        return profileMapper.selectList(new LambdaQueryWrapper<SmartWalletProfileEntity>()
                .eq(SmartWalletProfileEntity::getSource, SOURCE)
                .eq(SmartWalletProfileEntity::getQualified, Boolean.TRUE)
                .eq(SmartWalletProfileEntity::getWatchEnabled, Boolean.TRUE)
                .orderByDesc(SmartWalletProfileEntity::getWalletScore));
    }

    public void update(SmartWalletProfileEntity entity) {
        entity.setUpdatedAt(new Date());
        profileMapper.updateById(entity);
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
                wallet.setUpdatedAt(now);
                profileMapper.updateById(wallet);
                log.debug("WALLET_DECAY score walletAddress={} daysSince={} newScore={}", wallet.getWalletAddress(), daysSince, decayedScore);
            }
        }
    }

    // -------------------------------------------------------------------------
    // P2: Internal win-rate refresh — runs every 6 hours.
    // Computes real win rate from our own closed positions for each wallet and
    // blends it with the external leaderboard win rate (weight 0.4 internal / 0.6 external)
    // to correct for survivorship bias in the official leaderboard data.
    // -------------------------------------------------------------------------
    @Scheduled(fixedDelay = 6 * 3600_000L + 60_000L) // offset by 1 min so both jobs don't run simultaneously
    public void refreshInternalWinRate() {
        List<SmartWalletProfileEntity> wallets = profileMapper.selectList(
                new LambdaQueryWrapper<SmartWalletProfileEntity>()
                        .eq(SmartWalletProfileEntity::getSource, SOURCE));

        for (SmartWalletProfileEntity wallet : wallets) {
            // Count our own followed tasks that resulted in closed positions for this wallet
            List<SmartWalletFollowTaskEntity> followedTasks = followTaskMapper.selectList(
                    new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                            .eq(SmartWalletFollowTaskEntity::getWalletAddress, wallet.getWalletAddress())
                            .eq(SmartWalletFollowTaskEntity::getStatus, "FOLLOWED"));
            if (followedTasks.isEmpty()) {
                continue;
            }

            int total = 0, wins = 0;
            for (SmartWalletFollowTaskEntity task : followedTasks) {
                // Find the closed position for this task via market+outcome
                List<SmartWalletPositionEntity> closed = positionMapper.selectList(
                        new LambdaQueryWrapper<SmartWalletPositionEntity>()
                                .eq(SmartWalletPositionEntity::getMarketId, task.getMarketId())
                                .eq(SmartWalletPositionEntity::getOutcome, task.getOutcome())
                                .in(SmartWalletPositionEntity::getStatus, List.of("CLAIMED", "RESOLVED_UNCLAIMED")));
                for (SmartWalletPositionEntity pos : closed) {
                    total++;
                    if (pos.getRealizedPnlUsdc() != null && pos.getRealizedPnlUsdc().compareTo(BigDecimal.ZERO) > 0) {
                        wins++;
                    }
                }
            }
            if (total < 3) {
                continue; // not enough data to be meaningful
            }

            BigDecimal internalWinRate = BigDecimal.valueOf(wins)
                    .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
            // Blend: 40% internal (truth) + 60% external (leaderboard) to smooth noise
            BigDecimal externalWinRate = safe(wallet.getWinRate());
            BigDecimal blendedWinRate = internalWinRate.multiply(new BigDecimal("0.40"))
                    .add(externalWinRate.multiply(new BigDecimal("0.60")))
                    .setScale(4, RoundingMode.HALF_UP);

            // Re-score wallet with blended win rate
            BigDecimal newScore = scoringService.score(
                    blendedWinRate,
                    safe(wallet.getRealizedPnlUsdc()),
                    wallet.getTotalTrades() == null ? 0 : wallet.getTotalTrades(),
                    safe(wallet.getAverageRoiRate()),
                    safe(wallet.getMaxDrawdownRate())
            );
            wallet.setWinRate(blendedWinRate);
            wallet.setWalletScore(newScore);
            wallet.setUpdatedAt(new Date());
            profileMapper.updateById(wallet);
            log.info("WIN_RATE_REFRESH walletAddress={} internalWinRate={} blended={} newScore={} sampleSize={}",
                    wallet.getWalletAddress(), internalWinRate, blendedWinRate, newScore, total);
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
