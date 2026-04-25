package com.metaverse.coin.strategy.job.smartwallet.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletOrderAggregationService {

    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketMarketMetadataService marketMetadataService;

    public WalletSnapshot snapshot(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return WalletSnapshot.empty();
        }
        String normalizedWallet = walletAddress.trim();
        List<PolymarketApiClient.ClosedPosition> closedPositions = fetchClosedPositions(normalizedWallet);
        List<PolymarketApiClient.ActivePosition> activePositions = fetchActivePositions(normalizedWallet);
        List<PolymarketApiClient.UserTrade> trades = fetchUserTrades(normalizedWallet);
        Map<String, PolymarketApiClient.MarketMetadata> metadataByMarketId = loadMetadata(closedPositions, activePositions);
        Map<String, TradeCursor> tradeCursorByKey = buildTradeCursorByKey(trades);

        List<ClosedOrderSnapshot> closedOrders = buildClosedOrders(
                normalizedWallet,
                closedPositions,
                tradeCursorByKey,
                metadataByMarketId
        );
        List<ActivePositionSnapshot> activeOrderSnapshots = buildActivePositions(
                normalizedWallet,
                activePositions,
                tradeCursorByKey,
                metadataByMarketId
        );
        return WalletSnapshot.builder()
                .walletAddress(normalizedWallet)
                .closedOrders(closedOrders)
                .activePositions(activeOrderSnapshots)
                .build();
    }

    private List<PolymarketApiClient.ClosedPosition> fetchClosedPositions(String walletAddress) {
        try {
            return polymarketApiClient.fetchAllClosedPositions(walletAddress);
        } catch (Exception ex) {
            log.debug("Failed to fetch full closed positions for wallet={}: {}", walletAddress, ex.getMessage());
            return List.of();
        }
    }

    private List<PolymarketApiClient.ActivePosition> fetchActivePositions(String walletAddress) {
        try {
            return polymarketApiClient.fetchActivePositions(walletAddress);
        } catch (Exception ex) {
            log.debug("Failed to fetch active positions for wallet={}: {}", walletAddress, ex.getMessage());
            return List.of();
        }
    }

    private List<PolymarketApiClient.UserTrade> fetchUserTrades(String walletAddress) {
        try {
            return polymarketApiClient.fetchAllUserTrades(walletAddress);
        } catch (Exception ex) {
            log.debug("Failed to fetch full user trades for wallet={}: {}", walletAddress, ex.getMessage());
            return List.of();
        }
    }

    private List<ClosedOrderSnapshot> buildClosedOrders(String walletAddress,
                                                        List<PolymarketApiClient.ClosedPosition> closedPositions,
                                                        Map<String, TradeCursor> tradeCursorByKey,
                                                        Map<String, PolymarketApiClient.MarketMetadata> metadataByMarketId) {
        if (closedPositions == null || closedPositions.isEmpty()) {
            return List.of();
        }
        List<ClosedOrderSnapshot> result = new ArrayList<>();
        List<PolymarketApiClient.ClosedPosition> sorted = closedPositions.stream()
                .filter(position -> position != null)
                .sorted(Comparator.comparing(PolymarketApiClient.ClosedPosition::getClosedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (PolymarketApiClient.ClosedPosition position : sorted) {
            PolymarketApiClient.MarketMetadata metadata = metadataByMarketId.get(defaultString(position.getMarketId()));
            TradeCursor tradeCursor = tradeCursorByKey.get(tradeKey(position.getMarketId(), position.getOutcome()));
            Long settlementAt = position.getClosedAt();
            Long openedAt = firstNonNull(position.getOpenedAt(),
                    tradeCursor == null ? null : tradeCursor.consumeOpenTime(settlementAt));
            BigDecimal investedUsdc = safe(position.getSizeBought());
            BigDecimal realizedPnlUsdc = safe(position.getRealizedPnl()).add(safe(position.getCashPnl()));
            BigDecimal roiRate = investedUsdc.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : realizedPnlUsdc.divide(investedUsdc, 8, RoundingMode.HALF_UP);
            Long marketEndAt = resolveMarketEndAt(metadata, null);
            boolean earlySettlement = settlementAt != null && marketEndAt != null && settlementAt < marketEndAt;
            BigDecimal holdMinutes = openedAt == null || settlementAt == null
                    ? null
                    : BigDecimal.valueOf(Math.max(0L, settlementAt - openedAt))
                    .divide(new BigDecimal("60000"), 2, RoundingMode.HALF_UP);
            BigDecimal exitLeadMinutes = settlementAt == null || marketEndAt == null
                    ? null
                    : BigDecimal.valueOf(Math.max(0L, marketEndAt - settlementAt))
                    .divide(new BigDecimal("60000"), 2, RoundingMode.HALF_UP);
            result.add(ClosedOrderSnapshot.builder()
                    .walletAddress(walletAddress)
                    .marketId(position.getMarketId())
                    .question(resolveQuestion(position.getQuestion(), metadata))
                    .outcome(normalizeOutcome(position.getOutcome()))
                    .openedAt(asDate(openedAt))
                    .settlementAt(asDate(settlementAt))
                    .marketEndAt(marketEndAt)
                    .investedUsdc(investedUsdc)
                    .realizedPnlUsdc(realizedPnlUsdc)
                    .roiRate(roiRate)
                    .holdMinutes(holdMinutes)
                    .exitStyle(resolveClosedExitStyle(settlementAt, marketEndAt, realizedPnlUsdc))
                    .exitLeadMinutes(exitLeadMinutes)
                    .isWin(realizedPnlUsdc.compareTo(BigDecimal.ZERO) > 0)
                    .sourceConfidence(sourceConfidence(openedAt, marketEndAt, investedUsdc, settlementAt))
                    .earlySettlement(earlySettlement)
                    .build());
        }
        result.sort(Comparator.comparing(ClosedOrderSnapshot::getSettlementAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private List<ActivePositionSnapshot> buildActivePositions(String walletAddress,
                                                              List<PolymarketApiClient.ActivePosition> activePositions,
                                                              Map<String, TradeCursor> tradeCursorByKey,
                                                              Map<String, PolymarketApiClient.MarketMetadata> metadataByMarketId) {
        if (activePositions == null || activePositions.isEmpty()) {
            return List.of();
        }
        List<ActivePositionSnapshot> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (PolymarketApiClient.ActivePosition position : activePositions) {
            if (position == null || !StringUtils.hasText(position.getMarketId())) {
                continue;
            }
            PolymarketApiClient.MarketMetadata metadata = metadataByMarketId.get(defaultString(position.getMarketId()));
            TradeCursor tradeCursor = tradeCursorByKey.get(tradeKey(position.getMarketId(), position.getOutcome()));
            Long openedAt = firstNonNull(position.getOpenedAt(),
                    tradeCursor == null ? null : tradeCursor.remainingOpenTime());
            Long marketEndAt = resolveMarketEndAt(metadata, position.getMarketEndAt());
            boolean pendingSettlement = marketEndAt != null
                    && marketEndAt <= now
                    && position.getSettlementAt() == null;
            result.add(ActivePositionSnapshot.builder()
                    .walletAddress(walletAddress)
                    .marketId(position.getMarketId())
                    .question(resolveQuestion(position.getQuestion(), metadata))
                    .outcome(normalizeOutcome(position.getOutcome()))
                    .openedAt(asDate(openedAt))
                    .marketEndAt(marketEndAt)
                    .investedUsdc(safe(position.getInvestedUsdc()))
                    .currentValueUsdc(position.getCurrentValueUsdc())
                    .unrealizedPnlUsdc(position.getUnrealizedPnlUsdc())
                    .pendingSettlement(pendingSettlement)
                    .sourceConfidence(sourceConfidence(openedAt, marketEndAt,
                            safe(position.getInvestedUsdc()),
                            position.getSettlementAt()))
                    .build());
        }
        result.sort(Comparator.comparing(ActivePositionSnapshot::getOpenedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private Map<String, PolymarketApiClient.MarketMetadata> loadMetadata(
            List<PolymarketApiClient.ClosedPosition> closedPositions,
            List<PolymarketApiClient.ActivePosition> activePositions) {
        List<String> marketIds = new ArrayList<>();
        if (closedPositions != null) {
            for (PolymarketApiClient.ClosedPosition position : closedPositions) {
                if (position != null && StringUtils.hasText(position.getMarketId())) {
                    marketIds.add(position.getMarketId().trim());
                }
            }
        }
        if (activePositions != null) {
            for (PolymarketApiClient.ActivePosition position : activePositions) {
                if (position != null && StringUtils.hasText(position.getMarketId())) {
                    marketIds.add(position.getMarketId().trim());
                }
            }
        }
        List<String> normalizedIds = marketIds.stream().distinct().toList();
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Map<String, PolymarketApiClient.MarketMetadata> result = new LinkedHashMap<>();
        try {
            result.putAll(marketMetadataService.refreshBatchByConditionIds(normalizedIds, 20));
        } catch (Exception ex) {
            log.debug("Failed to refresh batch metadata for ids={}: {}", normalizedIds, ex.getMessage());
        }
        for (String marketId : normalizedIds) {
            if (result.containsKey(marketId)) {
                continue;
            }
            try {
                result.put(marketId, marketMetadataService.getBestEffortByConditionId(marketId, false, null, List.of()));
            } catch (Exception ex) {
                log.debug("Failed to resolve metadata for marketId={}: {}", marketId, ex.getMessage());
            }
        }
        return result;
    }

    private Map<String, TradeCursor> buildTradeCursorByKey(List<PolymarketApiClient.UserTrade> trades) {
        Map<String, List<PolymarketApiClient.UserTrade>> grouped = new LinkedHashMap<>();
        if (trades != null) {
            for (PolymarketApiClient.UserTrade trade : trades) {
                if (trade == null || !StringUtils.hasText(trade.getMarketId()) || trade.getTradedAt() == null) {
                    continue;
                }
                grouped.computeIfAbsent(tradeKey(trade.getMarketId(), trade.getOutcome()), key -> new ArrayList<>()).add(trade);
            }
        }
        Map<String, TradeCursor> cursorByKey = new HashMap<>();
        for (Map.Entry<String, List<PolymarketApiClient.UserTrade>> entry : grouped.entrySet()) {
            List<PolymarketApiClient.UserTrade> sorted = entry.getValue().stream()
                    .sorted(Comparator.comparing(PolymarketApiClient.UserTrade::getTradedAt))
                    .toList();
            cursorByKey.put(entry.getKey(), new TradeCursor(sorted));
        }
        return cursorByKey;
    }

    private String resolveClosedExitStyle(Long settlementAt, Long marketEndAt, BigDecimal realizedPnlUsdc) {
        if (settlementAt == null || marketEndAt == null) {
            return "UNKNOWN";
        }
        boolean earlySettlement = settlementAt < marketEndAt;
        if (earlySettlement) {
            if (realizedPnlUsdc.compareTo(BigDecimal.ZERO) > 0) {
                return "EARLY_PROFIT";
            }
            if (realizedPnlUsdc.compareTo(BigDecimal.ZERO) < 0) {
                return "EARLY_STOP";
            }
            return "UNKNOWN";
        }
        if (realizedPnlUsdc.compareTo(BigDecimal.ZERO) > 0) {
            return "SETTLED_WIN";
        }
        if (realizedPnlUsdc.compareTo(BigDecimal.ZERO) < 0) {
            return "SETTLED_LOSS";
        }
        return "UNKNOWN";
    }

    private BigDecimal sourceConfidence(Long openedAt, Long marketEndAt, BigDecimal investedUsdc, Long settledAt) {
        BigDecimal confidence = new BigDecimal("0.35");
        if (openedAt != null) {
            confidence = confidence.add(new BigDecimal("0.25"));
        }
        if (marketEndAt != null && settledAt != null) {
            confidence = confidence.add(new BigDecimal("0.25"));
        }
        if (safe(investedUsdc).compareTo(BigDecimal.ZERO) > 0) {
            confidence = confidence.add(new BigDecimal("0.15"));
        }
        return confidence.min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private Date asDate(Long epochMillis) {
        return epochMillis == null ? null : new Date(epochMillis);
    }

    private Long resolveMarketEndAt(PolymarketApiClient.MarketMetadata metadata, Long fallback) {
        if (metadata != null && metadata.getEndAt() != null) {
            return metadata.getEndAt();
        }
        return fallback;
    }

    private Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private String resolveQuestion(String question, PolymarketApiClient.MarketMetadata metadata) {
        if (StringUtils.hasText(question)) {
            return question;
        }
        return metadata == null ? null : metadata.getQuestion();
    }

    private String tradeKey(String marketId, String outcome) {
        return defaultString(marketId) + "|" + normalizeOutcome(outcome);
    }

    private String normalizeOutcome(String outcome) {
        return StringUtils.hasText(outcome) ? outcome.trim().toUpperCase() : "";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Data
    @Builder
    public static class WalletSnapshot {
        private String walletAddress;
        @Builder.Default
        private List<ClosedOrderSnapshot> closedOrders = List.of();
        @Builder.Default
        private List<ActivePositionSnapshot> activePositions = List.of();

        public static WalletSnapshot empty() {
            return WalletSnapshot.builder().build();
        }
    }

    @Data
    @Builder
    public static class ClosedOrderSnapshot {
        private String walletAddress;
        private String marketId;
        private String question;
        private String outcome;
        private Date openedAt;
        private Date settlementAt;
        private Long marketEndAt;
        private BigDecimal investedUsdc;
        private BigDecimal realizedPnlUsdc;
        private BigDecimal roiRate;
        private BigDecimal holdMinutes;
        private String exitStyle;
        private BigDecimal exitLeadMinutes;
        private Boolean isWin;
        private BigDecimal sourceConfidence;
        private Boolean earlySettlement;
    }

    @Data
    @Builder
    public static class ActivePositionSnapshot {
        private String walletAddress;
        private String marketId;
        private String question;
        private String outcome;
        private Date openedAt;
        private Long marketEndAt;
        private BigDecimal investedUsdc;
        private BigDecimal currentValueUsdc;
        private BigDecimal unrealizedPnlUsdc;
        private Boolean pendingSettlement;
        private BigDecimal sourceConfidence;
    }

    private static class TradeCursor {
        private final List<PolymarketApiClient.UserTrade> trades;
        private int nextIndex = 0;

        private TradeCursor(List<PolymarketApiClient.UserTrade> trades) {
            this.trades = trades == null ? List.of() : trades;
        }

        private Long consumeOpenTime(Long settledAt) {
            if (settledAt == null || trades.isEmpty()) {
                return null;
            }
            Long openedAt = null;
            while (nextIndex < trades.size()) {
                PolymarketApiClient.UserTrade trade = trades.get(nextIndex);
                if (trade == null || trade.getTradedAt() == null) {
                    nextIndex++;
                    continue;
                }
                if (trade.getTradedAt() > settledAt) {
                    break;
                }
                if (openedAt == null) {
                    openedAt = trade.getTradedAt();
                }
                nextIndex++;
            }
            return openedAt;
        }

        private Long remainingOpenTime() {
            while (nextIndex < trades.size()) {
                PolymarketApiClient.UserTrade trade = trades.get(nextIndex);
                if (trade == null || trade.getTradedAt() == null) {
                    nextIndex++;
                    continue;
                }
                return trade.getTradedAt();
            }
            return null;
        }
    }
}
