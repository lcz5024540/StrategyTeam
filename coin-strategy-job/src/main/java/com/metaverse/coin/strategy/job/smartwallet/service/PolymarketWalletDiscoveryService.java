package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketDiscoverySummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketTradeEventRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSelectionSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class PolymarketWalletDiscoveryService {

    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketSmartWalletMonitorService monitorService;
    private final SmartWalletSelectionService selectionService;
    private final SmartWalletProfileStoreService profileStoreService;

    public PolymarketDiscoverySummary analyzeTopWallets() {
        List<String> notes = new ArrayList<>();
        List<PolymarketApiClient.LeaderboardEntry> candidates;
        try {
            candidates = polymarketApiClient.fetchLeaderboard();
        } catch (Exception e) {
            log.warn("Failed to fetch Polymarket leaderboard", e);
            notes.add("Leaderboard fetch failed: " + rootMessage(e));
            return PolymarketDiscoverySummary.builder()
                    .candidateWallets(0)
                    .analyzedWallets(0)
                    .importedProfiles(0)
                    .importedTradeEvents(0)
                    .qualifiedCount(profileStoreService.listWatchEnabled().size())
                    .watchlistCount(profileStoreService.listWatchEnabled().size())
                    .notes(notes)
                    .build();
        }
        Map<String, PolymarketApiClient.LeaderboardEntry> deduped = new LinkedHashMap<>();
        for (PolymarketApiClient.LeaderboardEntry candidate : candidates) {
            if (candidate == null || !StringUtils.hasText(candidate.getWalletAddress())) {
                continue;
            }
            deduped.putIfAbsent(candidate.getWalletAddress().toLowerCase(), candidate);
        }

        int importedProfiles = 0;
        int analyzedWallets = 0;
        int importedTradeEvents = 0;

        int limit = Math.min(deduped.size(), monitorService.getAnalyzeTopN());
        for (PolymarketApiClient.LeaderboardEntry candidate : deduped.values().stream().limit(limit).toList()) {
            try {
                PolymarketWalletMetricsRequest metrics = buildMetrics(candidate);
                if (metrics == null || metrics.getTotalTrades() == null || metrics.getTotalTrades() == 0) {
                    continue;
                }
                monitorService.ingestWalletMetrics(metrics);
                importedProfiles++;
                analyzedWallets++;
            } catch (Exception e) {
                log.warn("Failed to analyze Polymarket wallet {}", candidate.getWalletAddress());
                notes.add("Analyze failed for " + candidate.getWalletAddress() + ": " + rootMessage(e));
            }
        }

        SmartWalletSelectionSummary selection = selectionService.refreshQualifiedWallets();
        try {
            importedTradeEvents = syncWatchlistTrades();
        } catch (Exception e) {
            log.warn("Failed to sync watchlist trades after discovery");
            notes.add("Trade sync failed after discovery: " + rootMessage(e));
        }

        notes.add("Candidates come from Polymarket official leaderboard and are re-ranked by local wallet score.");
        notes.add("Wallet metrics are calculated from official closed positions and recent trade data.");
        return PolymarketDiscoverySummary.builder()
                .candidateWallets(deduped.size())
                .analyzedWallets(analyzedWallets)
                .importedProfiles(importedProfiles)
                .importedTradeEvents(importedTradeEvents)
                .qualifiedCount(selection.getQualifiedCount())
                .watchlistCount(profileStoreService.listWatchEnabled().size())
                .notes(notes)
                .build();
    }

    public int syncWatchlistTrades() {
        int importedTradeEvents = 0;
        for (SmartWalletProfileEntity wallet : profileStoreService.listWatchEnabled()) {
            List<PolymarketApiClient.UserTrade> trades;
            try {
                trades = polymarketApiClient.fetchUserTrades(wallet.getWalletAddress());
            } catch (Exception e) {
                log.warn("Skip syncing wallet {} because Polymarket trades request failed: {}",
                        wallet.getWalletAddress(), rootMessage(e));
                continue;
            }
            for (PolymarketApiClient.UserTrade trade : trades) {
                if (trade == null || !StringUtils.hasText(trade.getTradeId())) {
                    continue;
                }
                try {
                    monitorService.ingestTradeEvent(toTradeEventRequest(trade, wallet));
                    importedTradeEvents++;
                } catch (Exception e) {
                    log.warn("Skip trade {} for wallet {}: {}",
                            trade.getTradeId(), wallet.getWalletAddress(), rootMessage(e));
                }
            }
        }
        return importedTradeEvents;
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.discovery-fixed-delay-ms:3600000}")
    public void scheduledDiscoverWallets() {
        if (!monitorService.isDiscoveryEnabled()) {
            return;
        }
        try {
            analyzeTopWallets();
        } catch (Exception e) {
            log.warn("Scheduled Polymarket discovery failed: {}", rootMessage(e));
        }
    }

    @Scheduled(fixedDelayString = "${smart-wallet.polymarket.event-sync-fixed-delay-ms:15000}")
    public void scheduledSyncWatchlistTrades() {
        if (!monitorService.isEventSyncEnabled()) {
            return;
        }
        try {
            syncWatchlistTrades();
        } catch (Exception e) {
            log.warn("Scheduled Polymarket trade sync failed: {}", rootMessage(e));
        }
    }

    private PolymarketWalletMetricsRequest buildMetrics(PolymarketApiClient.LeaderboardEntry candidate) {
        List<PolymarketApiClient.ClosedPosition> positions = polymarketApiClient.fetchClosedPositions(candidate.getWalletAddress());
        if (CollectionUtils.isEmpty(positions)) {
            return null;
        }

        PolymarketApiClient.WalletProfile profile = null;
        try {
            profile = polymarketApiClient.fetchProfile(candidate.getWalletAddress());
        } catch (Exception e) {
            log.debug("Failed to fetch Polymarket profile for {}", candidate.getWalletAddress(), e);
        }

        BigDecimal realizedPnl = BigDecimal.ZERO;
        BigDecimal totalSize = BigDecimal.ZERO;
        BigDecimal roiSum = BigDecimal.ZERO;
        int roiCount = 0;
        int wins = 0;
        long lastTradeAt = 0L;

        List<PolymarketApiClient.ClosedPosition> orderedPositions = positions.stream()
                .sorted(Comparator.comparing(PolymarketApiClient.ClosedPosition::getClosedAt,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();

        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peakEquity = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (PolymarketApiClient.ClosedPosition position : orderedPositions) {
            BigDecimal pnl = safe(position.getRealizedPnl()).add(safe(position.getCashPnl()));
            BigDecimal size = safe(position.getSizeBought());
            realizedPnl = realizedPnl.add(pnl);
            totalSize = totalSize.add(size);
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                wins++;
            }
            if (size.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal roi = pnl.divide(size, 6, RoundingMode.HALF_UP);
                roiSum = roiSum.add(roi.max(BigDecimal.ZERO).min(BigDecimal.ONE));
                roiCount++;
            }

            equity = equity.add(pnl);
            if (equity.compareTo(peakEquity) > 0) {
                peakEquity = equity;
            }
            BigDecimal denominator = peakEquity.max(BigDecimal.ONE);
            BigDecimal drawdown = peakEquity.subtract(equity).max(BigDecimal.ZERO)
                    .divide(denominator, 6, RoundingMode.HALF_UP)
                    .min(BigDecimal.ONE);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
            if (position.getClosedAt() != null) {
                lastTradeAt = Math.max(lastTradeAt, position.getClosedAt());
            }
        }

        int totalTrades = orderedPositions.size();
        String alias = profile != null && StringUtils.hasText(profile.getAlias()) ? profile.getAlias() : candidate.getAlias();
        BigDecimal winRate = BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP);
        BigDecimal averageSize = totalTrades == 0 ? BigDecimal.ZERO
                : totalSize.divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP);
        BigDecimal averageRoi = roiCount == 0 ? BigDecimal.ZERO
                : roiSum.divide(BigDecimal.valueOf(roiCount), 4, RoundingMode.HALF_UP);

        PolymarketWalletMetricsRequest request = new PolymarketWalletMetricsRequest();
        request.setWalletAddress(candidate.getWalletAddress());
        request.setChain("polygon");
        request.setAlias(alias);
        request.setWinRate(winRate);
        request.setRealizedPnlUsdc(realizedPnl.setScale(4, RoundingMode.HALF_UP));
        request.setTotalTrades(totalTrades);
        request.setAverageSizeUsdc(averageSize);
        request.setAverageRoiRate(averageRoi);
        request.setMaxDrawdownRate(maxDrawdown.setScale(4, RoundingMode.HALF_UP));
        request.setLastTradeAt(lastTradeAt > 0 ? new Date(lastTradeAt) : null);
        return request;
    }

    private PolymarketTradeEventRequest toTradeEventRequest(PolymarketApiClient.UserTrade trade,
                                                            SmartWalletProfileEntity wallet) {
        PolymarketTradeEventRequest request = new PolymarketTradeEventRequest();
        request.setWalletAddress(wallet.getWalletAddress());
        request.setChain(wallet.getChain());
        request.setExternalTradeId(trade.getTradeId());
        request.setMarketId(trade.getMarketId());
        request.setQuestion(trade.getQuestion());
        request.setOutcome(trade.getOutcome());
        request.setSide(trade.getSide());
        request.setPrice(safe(trade.getPrice()));
        request.setSizeUsdc(safe(trade.getSize()));
        request.setOpenedAt(trade.getTradedAt() == null ? new Date() : new Date(trade.getTradedAt()));
        return request;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.getMessage() : current.getMessage();
    }
}
