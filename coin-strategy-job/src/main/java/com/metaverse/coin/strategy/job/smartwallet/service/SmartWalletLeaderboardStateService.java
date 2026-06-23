package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletLeaderboardStateService {

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletProfileStoreService profileStoreService;

    private final Map<String, Integer> latestLeaderboardRanks = new ConcurrentHashMap<>();

    private volatile Date lastSnapshotAt;

    public void refreshSnapshot(List<PolymarketApiClient.LeaderboardEntry> entries) {
        Map<String, Integer> refreshed = new LinkedHashMap<>();
        int rank = 1;
        for (PolymarketApiClient.LeaderboardEntry entry : entries) {
            if (entry == null || !StringUtils.hasText(entry.getWalletAddress())) {
                continue;
            }
            refreshed.putIfAbsent(normalize(entry.getWalletAddress()), rank++);
        }
        latestLeaderboardRanks.clear();
        latestLeaderboardRanks.putAll(refreshed);
        lastSnapshotAt = new Date();
    }

    public boolean hasSnapshot() {
        return lastSnapshotAt != null && !latestLeaderboardRanks.isEmpty();
    }

    public boolean isCurrentLeaderboardWallet(String walletAddress) {
        if (!hasSnapshot()) {
            return true;
        }
        return latestLeaderboardRanks.containsKey(normalize(walletAddress));
    }

    public BigDecimal adjustWalletScore(String walletAddress, BigDecimal baseScore) {
        return adjustWalletScore(walletAddress, baseScore, null);
    }

    public BigDecimal adjustWalletScore(SmartWalletProfileEntity profile, SmartWalletTradeEventEntity event) {
        if (profile == null) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return adjustWalletScore(profile.getWalletAddress(), profile.getWalletScore(), event);
    }

    public BigDecimal adjustWalletScore(String walletAddress,
                                        BigDecimal baseScore,
                                        SmartWalletTradeEventEntity event) {
        BigDecimal normalizedBase = normalizeScore(baseScore);
        if (!StringUtils.hasText(walletAddress) || !hasSnapshot()) {
            return normalizedBase;
        }
        if (isCurrentLeaderboardWallet(walletAddress)) {
            return normalizedBase;
        }

        BigDecimal matchedPeerScore = resolveMatchedCurrentWalletScore(walletAddress, event);
        if (matchedPeerScore != null && matchedPeerScore.compareTo(BigDecimal.ZERO) > 0) {
            return normalizeScore(matchedPeerScore);
        }

        BigDecimal staleFactor = properties.getPolymarket().getStaleAddressWeightFactor();
        BigDecimal effectiveFactor = staleFactor == null || staleFactor.compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.35")
                : staleFactor.min(BigDecimal.ONE);
        return normalizeScore(normalizedBase.multiply(effectiveFactor));
    }

    private BigDecimal resolveMatchedCurrentWalletScore(String walletAddress,
                                                        SmartWalletTradeEventEntity event) {
        if (event == null
                || !StringUtils.hasText(event.getMarketId())
                || !StringUtils.hasText(event.getOutcome())
                || !StringUtils.hasText(event.getSide())) {
            return null;
        }

        int windowSeconds = properties.getSignalRisk().getConsensusWindowSeconds() == null
                ? 180
                : Math.max(properties.getSignalRisk().getConsensusWindowSeconds(), 30);
        Date from = new Date(System.currentTimeMillis() - windowSeconds * 1000L);
        List<SmartWalletTradeEventEntity> peerEvents = tradeEventMapper.selectList(
                new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                        .eq(SmartWalletTradeEventEntity::getMarketId, event.getMarketId())
                        .eq(SmartWalletTradeEventEntity::getOutcome, event.getOutcome())
                        .eq(SmartWalletTradeEventEntity::getSide, event.getSide())
                        .ge(SmartWalletTradeEventEntity::getOpenedAt, from)
                        .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                        .last("limit 20"));
        if (peerEvents.isEmpty()) {
            return null;
        }

        Map<String, SmartWalletProfileEntity> watchlistProfiles = profileStoreService.listWatchEnabled().stream()
                .filter(item -> StringUtils.hasText(item.getWalletAddress()))
                .collect(Collectors.toMap(
                        item -> normalize(item.getWalletAddress()),
                        item -> item,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<BigDecimal> peerScores = peerEvents.stream()
                .filter(item -> item != null && StringUtils.hasText(item.getWalletAddress()))
                .filter(item -> !normalize(item.getWalletAddress()).equals(normalize(walletAddress)))
                .filter(item -> isCurrentLeaderboardWallet(item.getWalletAddress()))
                .map(item -> watchlistProfiles.get(normalize(item.getWalletAddress())))
                .filter(item -> item != null && item.getWalletScore() != null)
                .map(SmartWalletProfileEntity::getWalletScore)
                .map(this::normalizeScore)
                .toList();
        if (peerScores.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal peerScore : peerScores) {
            total = total.add(peerScore);
        }
        return total.divide(BigDecimal.valueOf(peerScores.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeScore(BigDecimal value) {
        BigDecimal score = value == null ? BigDecimal.ZERO : value;
        if (score.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        if (score.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
        }
        return score.setScale(4, RoundingMode.HALF_UP);
    }

    private String normalize(String walletAddress) {
        return walletAddress == null ? "" : walletAddress.trim().toLowerCase();
    }
}
