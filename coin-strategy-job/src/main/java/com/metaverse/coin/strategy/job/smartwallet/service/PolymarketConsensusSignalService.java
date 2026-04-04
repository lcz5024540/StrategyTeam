package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PolymarketConsensusSignalService {

    private final SmartWalletProperties properties;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletProfileStoreService profileStoreService;

    public List<ConsensusSignalView> listRecentConsensusSignals() {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        return listConsensusSignals(risk.getConsensusWindowSeconds(), null, null, null, 100);
    }

    public List<ConsensusSignalView> listConsensusSignals(Integer windowSeconds,
                                                          String marketId,
                                                          String outcome,
                                                          String side,
                                                          Integer limit) {
        Map<String, SmartWalletProfileEntity> watchlistProfiles = profileStoreService.listWatchEnabled().stream()
                .collect(Collectors.toMap(item -> item.getWalletAddress().toLowerCase(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<SmartWalletTradeEventEntity> events = loadRecentEvents(windowSeconds, marketId, outcome, side, limit);
        Map<String, ConsensusAccumulator> grouped = new LinkedHashMap<>();

        for (SmartWalletTradeEventEntity event : events) {
            SmartWalletProfileEntity profile = watchlistProfiles.get(normalize(event.getWalletAddress()));
            if (profile == null) {
                continue;
            }
            String key = String.join("|",
                    nullSafe(event.getMarketId()),
                    nullSafe(event.getOutcome()),
                    nullSafe(event.getSide()));
            grouped.computeIfAbsent(key, ignored -> new ConsensusAccumulator(
                    event.getMarketId(),
                    event.getQuestion(),
                    event.getOutcome(),
                    event.getSide()))
                    .add(event, profile);
        }

        return grouped.values().stream()
                .map(ConsensusAccumulator::toView)
                .sorted(Comparator.comparing(ConsensusSignalView::getConsensusScore).reversed()
                        .thenComparing(ConsensusSignalView::getDistinctWallets, Comparator.reverseOrder()))
                .limit(normalizeLimit(limit))
                .toList();
    }

    public ConsensusSignalView buildConsensusForEvent(SmartWalletTradeEventEntity currentEvent) {
        return listConsensusSignals(properties.getSignalRisk().getConsensusWindowSeconds(),
                        currentEvent.getMarketId(),
                        currentEvent.getOutcome(),
                        currentEvent.getSide(),
                        20)
                .stream()
                .filter(item -> equalsIgnoreCase(item.getMarketId(), currentEvent.getMarketId()))
                .filter(item -> equalsIgnoreCase(item.getOutcome(), currentEvent.getOutcome()))
                .filter(item -> equalsIgnoreCase(item.getSide(), currentEvent.getSide()))
                .findFirst()
                .orElse(ConsensusSignalView.builder()
                        .marketId(currentEvent.getMarketId())
                        .question(currentEvent.getQuestion())
                        .outcome(currentEvent.getOutcome())
                        .side(currentEvent.getSide())
                        .distinctWallets(0)
                        .totalTriggerSizeUsdc(BigDecimal.ZERO)
                        .averageTriggerPrice(BigDecimal.ZERO)
                        .consensusScore(BigDecimal.ZERO)
                        .latestOpenedAt(currentEvent.getOpenedAt())
                        .walletAddresses(new ArrayList<>())
                        .build());
    }

    private List<SmartWalletTradeEventEntity> loadRecentEvents(Integer windowSeconds,
                                                               String marketId,
                                                               String outcome,
                                                               String side,
                                                               Integer limit) {
        int effectiveWindow = windowSeconds == null || windowSeconds <= 0
                ? properties.getSignalRisk().getConsensusWindowSeconds()
                : windowSeconds;
        Date from = new Date(System.currentTimeMillis() - effectiveWindow * 1000L);
        LambdaQueryWrapper<SmartWalletTradeEventEntity> query = new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .ge(SmartWalletTradeEventEntity::getOpenedAt, from)
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt);
        if (StringUtils.hasText(marketId)) {
            query.eq(SmartWalletTradeEventEntity::getMarketId, marketId.trim());
        }
        if (StringUtils.hasText(outcome)) {
            query.eq(SmartWalletTradeEventEntity::getOutcome, outcome.trim().toUpperCase());
        }
        if (StringUtils.hasText(side)) {
            query.eq(SmartWalletTradeEventEntity::getSide, side.trim().toUpperCase());
        }
        query.last("limit " + Math.max(1, normalizeLimit(limit) * 20));
        return tradeEventMapper.selectList(query);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 200);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    @Data
    private static class ConsensusAccumulator {
        private final String marketId;
        private final String question;
        private final String outcome;
        private final String side;
        private final Map<String, BigDecimal> walletScores = new LinkedHashMap<>();
        private final List<String> walletAddresses = new ArrayList<>();
        private BigDecimal totalTriggerSizeUsdc = BigDecimal.ZERO;
        private BigDecimal totalTriggerPrice = BigDecimal.ZERO;
        private int priceCount = 0;
        private Date latestOpenedAt;

        private void add(SmartWalletTradeEventEntity event, SmartWalletProfileEntity profile) {
            String wallet = profile.getWalletAddress().toLowerCase();
            if (!walletScores.containsKey(wallet)) {
                walletScores.put(wallet, profile.getWalletScore() == null ? BigDecimal.ZERO : profile.getWalletScore());
                walletAddresses.add(profile.getWalletAddress());
            }
            totalTriggerSizeUsdc = totalTriggerSizeUsdc.add(event.getSizeUsdc() == null ? BigDecimal.ZERO : event.getSizeUsdc());
            if (event.getPrice() != null) {
                totalTriggerPrice = totalTriggerPrice.add(event.getPrice());
                priceCount++;
            }
            if (latestOpenedAt == null || (event.getOpenedAt() != null && event.getOpenedAt().after(latestOpenedAt))) {
                latestOpenedAt = event.getOpenedAt();
            }
        }

        private ConsensusSignalView toView() {
            BigDecimal consensusScore = walletScores.isEmpty()
                    ? BigDecimal.ZERO
                    : walletScores.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(walletScores.size()), 4, RoundingMode.HALF_UP);
            BigDecimal averagePrice = priceCount == 0
                    ? BigDecimal.ZERO
                    : totalTriggerPrice.divide(BigDecimal.valueOf(priceCount), 4, RoundingMode.HALF_UP);
            return ConsensusSignalView.builder()
                    .marketId(marketId)
                    .question(question)
                    .outcome(outcome)
                    .side(side)
                    .distinctWallets(walletScores.size())
                    .totalTriggerSizeUsdc(totalTriggerSizeUsdc.setScale(4, RoundingMode.HALF_UP))
                    .averageTriggerPrice(averagePrice)
                    .consensusScore(consensusScore)
                    .latestOpenedAt(latestOpenedAt)
                    .walletAddresses(walletAddresses)
                    .build();
        }
    }
}
