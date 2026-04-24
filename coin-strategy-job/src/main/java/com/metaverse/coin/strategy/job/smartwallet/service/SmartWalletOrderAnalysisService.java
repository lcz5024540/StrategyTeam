package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletOrderAnalysisView;
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
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletOrderAnalysisService {

    private static final String SOURCE = "POLYMARKET";
    private static final long EARLY_EXIT_TOLERANCE_MS = 15L * 60_000L;

    private final SmartWalletOrderAnalysisMapper orderAnalysisMapper;
    private final PolymarketApiClient polymarketApiClient;
    private final PolymarketMarketMetadataService marketMetadataService;

    public List<SmartWalletOrderAnalysisEntity> analyzeAndPersist(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        String normalizedWallet = walletAddress.trim();

        List<PolymarketApiClient.ClosedPosition> closedPositions;
        try {
            closedPositions = polymarketApiClient.fetchClosedPositions(normalizedWallet);
        } catch (Exception ex) {
            log.debug("Failed to fetch closed positions for wallet={}: {}", normalizedWallet, ex.getMessage());
            return listPersisted(normalizedWallet);
        }
        if (closedPositions == null || closedPositions.isEmpty()) {
            return listPersisted(normalizedWallet);
        }

        List<PolymarketApiClient.UserTrade> trades;
        try {
            trades = polymarketApiClient.fetchUserTrades(normalizedWallet, 200);
        } catch (Exception ex) {
            log.debug("Failed to fetch user trades for wallet={}: {}", normalizedWallet, ex.getMessage());
            trades = List.of();
        }

        Map<String, TradeCursor> tradeCursorByKey = buildTradeCursorByKey(trades);
        Map<String, SmartWalletOrderAnalysisEntity> existingByKey = new HashMap<>();
        for (SmartWalletOrderAnalysisEntity existing : listPersisted(normalizedWallet)) {
            existingByKey.put(uniqueKey(existing.getMarketId(), existing.getOutcome(), existing.getClosedAt()), existing);
        }

        List<SmartWalletOrderAnalysisEntity> built = new ArrayList<>();
        List<PolymarketApiClient.ClosedPosition> sortedPositions = closedPositions.stream()
                .filter(position -> position != null)
                .sorted(Comparator.comparing(PolymarketApiClient.ClosedPosition::getClosedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        for (PolymarketApiClient.ClosedPosition position : sortedPositions) {
            SmartWalletOrderAnalysisEntity entity = buildEntity(normalizedWallet, position,
                    tradeCursorByKey.get(tradeKey(position.getMarketId(), position.getOutcome())));
            String uniqueKey = uniqueKey(entity.getMarketId(), entity.getOutcome(), entity.getClosedAt());
            SmartWalletOrderAnalysisEntity existing = existingByKey.get(uniqueKey);
            if (existing == null) {
                entity.setCreatedAt(new Date());
                entity.setUpdatedAt(new Date());
                orderAnalysisMapper.insert(entity);
                built.add(entity);
            } else {
                entity.setId(existing.getId());
                entity.setCreatedAt(existing.getCreatedAt());
                entity.setUpdatedAt(new Date());
                orderAnalysisMapper.updateById(entity);
                built.add(entity);
            }
        }

        return built.stream()
                .sorted(Comparator.comparing(SmartWalletOrderAnalysisEntity::getClosedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<SmartWalletOrderAnalysisEntity> listByWalletAddress(String walletAddress,
                                                                    boolean refresh,
                                                                    String exitStyle,
                                                                    Boolean isWin) {
        List<SmartWalletOrderAnalysisEntity> rows = refresh
                ? analyzeAndPersist(walletAddress)
                : listPersisted(walletAddress);
        if ((rows == null || rows.isEmpty()) && !refresh) {
            rows = analyzeAndPersist(walletAddress);
        }
        String normalizedExitStyle = StringUtils.hasText(exitStyle) ? exitStyle.trim().toUpperCase(Locale.ROOT) : null;
        return rows.stream()
                .filter(row -> normalizedExitStyle == null
                        || normalizedExitStyle.equalsIgnoreCase(defaultString(row.getExitStyle())))
                .filter(row -> isWin == null || isWin.equals(Boolean.TRUE.equals(row.getIsWin())))
                .sorted(Comparator.comparing(SmartWalletOrderAnalysisEntity::getClosedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<WalletOrderAnalysisView> toViews(List<SmartWalletOrderAnalysisEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(this::toView).toList();
    }

    public WalletOrderAnalysisView toView(SmartWalletOrderAnalysisEntity row) {
        if (row == null) {
            return null;
        }
        return WalletOrderAnalysisView.builder()
                .walletAddress(row.getWalletAddress())
                .marketId(row.getMarketId())
                .question(row.getQuestion())
                .outcome(row.getOutcome())
                .openedAt(row.getOpenedAt())
                .closedAt(row.getClosedAt())
                .marketEndAt(row.getMarketEndAt())
                .investedUsdc(scale(row.getInvestedUsdc(), 4))
                .realizedPnlUsdc(scale(row.getRealizedPnlUsdc(), 4))
                .roiRate(scale(row.getRoiRate(), 4))
                .holdMinutes(scale(row.getHoldMinutes(), 2))
                .exitStyle(row.getExitStyle())
                .exitLeadMinutes(scale(row.getExitLeadMinutes(), 2))
                .isWin(Boolean.TRUE.equals(row.getIsWin()))
                .sourceConfidence(scale(row.getSourceConfidence(), 4))
                .build();
    }

    private List<SmartWalletOrderAnalysisEntity> listPersisted(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return List.of();
        }
        return orderAnalysisMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderAnalysisEntity>()
                .eq(SmartWalletOrderAnalysisEntity::getWalletAddress, walletAddress.trim())
                .eq(SmartWalletOrderAnalysisEntity::getSource, SOURCE)
                .orderByDesc(SmartWalletOrderAnalysisEntity::getClosedAt));
    }

    private SmartWalletOrderAnalysisEntity buildEntity(String walletAddress,
                                                       PolymarketApiClient.ClosedPosition position,
                                                       TradeCursor tradeCursor) {
        Date now = new Date();
        Long openedAt = tradeCursor == null ? null : tradeCursor.consumeOpenTime(position.getClosedAt());
        PolymarketApiClient.MarketMetadata metadata = null;
        try {
            metadata = marketMetadataService.getBestEffortByConditionId(
                    position.getMarketId(),
                    false,
                    position.getQuestion(),
                    StringUtils.hasText(position.getOutcome()) ? List.of(position.getOutcome()) : List.of()
            );
        } catch (Exception ex) {
            log.debug("Failed to resolve metadata for wallet={} marketId={}: {}", walletAddress, position.getMarketId(), ex.getMessage());
        }

        BigDecimal investedUsdc = safe(position.getSizeBought());
        BigDecimal realizedPnlUsdc = safe(position.getRealizedPnl()).add(safe(position.getCashPnl()));
        BigDecimal roiRate = investedUsdc.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : realizedPnlUsdc.divide(investedUsdc, 8, RoundingMode.HALF_UP);
        Long closedAt = position.getClosedAt();
        Long marketEndAt = metadata == null ? null : metadata.getEndAt();
        String exitStyle = resolveExitStyle(closedAt, marketEndAt, realizedPnlUsdc);
        BigDecimal holdMinutes = openedAt == null || closedAt == null
                ? null
                : BigDecimal.valueOf(Math.max(0L, closedAt - openedAt))
                .divide(new BigDecimal("60000"), 2, RoundingMode.HALF_UP);
        BigDecimal exitLeadMinutes = closedAt == null || marketEndAt == null
                ? null
                : BigDecimal.valueOf(Math.max(0L, marketEndAt - closedAt))
                .divide(new BigDecimal("60000"), 2, RoundingMode.HALF_UP);

        return new SmartWalletOrderAnalysisEntity()
                .setWalletAddress(walletAddress)
                .setSource(SOURCE)
                .setMarketId(position.getMarketId())
                .setQuestion(position.getQuestion())
                .setOutcome(normalizeOutcome(position.getOutcome()))
                .setOpenedAt(openedAt == null ? null : new Date(openedAt))
                .setClosedAt(closedAt == null ? null : new Date(closedAt))
                .setMarketEndAt(marketEndAt)
                .setAverageEntryPrice(safe(position.getAvgPrice()))
                .setInvestedUsdc(investedUsdc)
                .setRealizedPnlUsdc(realizedPnlUsdc)
                .setRoiRate(roiRate)
                .setHoldMinutes(holdMinutes)
                .setExitStyle(exitStyle)
                .setExitLeadMinutes(exitLeadMinutes)
                .setIsWin(realizedPnlUsdc.compareTo(BigDecimal.ZERO) > 0)
                .setSourceConfidence(sourceConfidence(openedAt, marketEndAt, investedUsdc, closedAt))
                .setCreatedAt(now)
                .setUpdatedAt(now);
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

    private String resolveExitStyle(Long closedAt, Long marketEndAt, BigDecimal realizedPnlUsdc) {
        if (closedAt == null || marketEndAt == null) {
            return "UNKNOWN";
        }
        boolean earlyExit = closedAt + EARLY_EXIT_TOLERANCE_MS < marketEndAt;
        if (earlyExit) {
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

    private BigDecimal sourceConfidence(Long openedAt, Long marketEndAt, BigDecimal investedUsdc, Long closedAt) {
        BigDecimal confidence = new BigDecimal("0.35");
        if (openedAt != null) {
            confidence = confidence.add(new BigDecimal("0.25"));
        }
        if (marketEndAt != null && closedAt != null) {
            confidence = confidence.add(new BigDecimal("0.25"));
        }
        if (safe(investedUsdc).compareTo(BigDecimal.ZERO) > 0) {
            confidence = confidence.add(new BigDecimal("0.15"));
        }
        return confidence.min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
    }

    private String tradeKey(String marketId, String outcome) {
        return defaultString(marketId) + "|" + normalizeOutcome(outcome);
    }

    private String uniqueKey(String marketId, String outcome, Date closedAt) {
        return tradeKey(marketId, outcome) + "|" + (closedAt == null ? "NULL" : closedAt.getTime());
    }

    private String normalizeOutcome(String outcome) {
        return StringUtils.hasText(outcome) ? outcome.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static class TradeCursor {
        private final List<PolymarketApiClient.UserTrade> trades;
        private int nextIndex = 0;

        private TradeCursor(List<PolymarketApiClient.UserTrade> trades) {
            this.trades = trades == null ? List.of() : trades;
        }

        private Long consumeOpenTime(Long closedAt) {
            if (closedAt == null || trades.isEmpty()) {
                return null;
            }
            Long openedAt = null;
            while (nextIndex < trades.size()) {
                PolymarketApiClient.UserTrade trade = trades.get(nextIndex);
                if (trade == null || trade.getTradedAt() == null) {
                    nextIndex++;
                    continue;
                }
                if (trade.getTradedAt() > closedAt) {
                    break;
                }
                if (openedAt == null) {
                    openedAt = trade.getTradedAt();
                }
                nextIndex++;
            }
            return openedAt;
        }
    }
}
