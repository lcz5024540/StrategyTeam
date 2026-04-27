package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletOrderStatsView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class WalletOrderStatsService {

    private final WalletOrderAggregationService walletOrderAggregationService;

    public WalletOrderStatsView summarize(String walletAddress, String window) {
        WalletOrderAggregationService.WalletSnapshot snapshot = walletOrderAggregationService.snapshot(walletAddress);
        return summarize(snapshot, normalizeWindow(window));
    }

    public WalletOrderStatsView summarize(String walletAddress,
                                          List<SmartWalletOrderAnalysisEntity> closedRows,
                                          String window) {
        List<WalletOrderAggregationService.ActivePositionSnapshot> activePositions =
                walletOrderAggregationService.activePositions(walletAddress);
        return summarize(walletAddress, closedRows, activePositions, normalizeWindow(window));
    }

    public WalletOrderStatsView summarize(WalletOrderAggregationService.WalletSnapshot snapshot, String window) {
        String normalizedWindow = normalizeWindow(window);
        long cutoff = cutoffMillis(normalizedWindow);
        List<WalletOrderAggregationService.ClosedOrderSnapshot> closedOrders = filterClosedOrders(snapshot.getClosedOrders(), normalizedWindow, cutoff);
        List<WalletOrderAggregationService.ActivePositionSnapshot> activePositions = filterActivePositions(snapshot.getActivePositions(), normalizedWindow, cutoff);

        int winCount = 0;
        int lossCount = 0;
        int settledCount = 0;
        int settledWins = 0;
        int earlySettlementCount = 0;
        BigDecimal closedRealizedPnlUsdc = BigDecimal.ZERO;
        BigDecimal activeUnrealizedPnlUsdc = BigDecimal.ZERO;
        BigDecimal totalRoi = BigDecimal.ZERO;
        int roiSamples = 0;
        int pendingSettlementCount = 0;
        int activePositionCount = 0;

        for (WalletOrderAggregationService.ClosedOrderSnapshot order : closedOrders) {
            if (order == null) {
                continue;
            }
            BigDecimal realizedPnl = safe(order.getRealizedPnlUsdc());
            closedRealizedPnlUsdc = closedRealizedPnlUsdc.add(realizedPnl);
            if (realizedPnl.compareTo(BigDecimal.ZERO) > 0) {
                winCount++;
            } else if (realizedPnl.compareTo(BigDecimal.ZERO) < 0) {
                lossCount++;
            }
            if (Boolean.TRUE.equals(order.getEarlySettlement())) {
                earlySettlementCount++;
            } else if (!"UNKNOWN".equalsIgnoreCase(defaultString(order.getExitStyle()))) {
                settledCount++;
                if (realizedPnl.compareTo(BigDecimal.ZERO) > 0) {
                    settledWins++;
                }
            }
            if (order.getRoiRate() != null) {
                totalRoi = totalRoi.add(order.getRoiRate());
                roiSamples++;
            }
        }

        for (WalletOrderAggregationService.ActivePositionSnapshot position : activePositions) {
            if (position == null) {
                continue;
            }
            if (Boolean.TRUE.equals(position.getPendingSettlement())) {
                pendingSettlementCount++;
                continue;
            }
            activePositionCount++;
            if (position.getUnrealizedPnlUsdc() != null) {
                activeUnrealizedPnlUsdc = activeUnrealizedPnlUsdc.add(position.getUnrealizedPnlUsdc());
            }
        }

        int closedOrderCount = closedOrders.size();
        int drawCount = closedOrderCount - winCount - lossCount;
        return WalletOrderStatsView.builder()
                .walletAddress(snapshot.getWalletAddress())
                .window(normalizedWindow)
                .generatedAt(new Date())
                .closedOrderCount(closedOrderCount)
                .winCount(winCount)
                .lossCount(lossCount)
                .drawCount(drawCount)
                .closedWinRate(ratio(winCount, winCount + lossCount))
                .closedRealizedPnlUsdc(scale(closedRealizedPnlUsdc, 4))
                .activeUnrealizedPnlUsdc(scale(activeUnrealizedPnlUsdc, 4))
                .netPnlUsdc(scale(closedRealizedPnlUsdc.add(activeUnrealizedPnlUsdc), 4))
                .averageRoiRate(roiSamples == 0
                        ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                        : totalRoi.divide(BigDecimal.valueOf(roiSamples), 4, RoundingMode.HALF_UP))
                .activePositionCount(activePositionCount)
                .pendingSettlementCount(pendingSettlementCount)
                .earlySettlementRate(ratio(earlySettlementCount, closedOrderCount))
                .settlementWinRate(ratio(settledWins, settledCount))
                .build();
    }

    private WalletOrderStatsView summarize(String walletAddress,
                                           List<SmartWalletOrderAnalysisEntity> closedRows,
                                           List<WalletOrderAggregationService.ActivePositionSnapshot> activePositions,
                                           String window) {
        String normalizedWindow = normalizeWindow(window);
        long cutoff = cutoffMillis(normalizedWindow);
        List<SmartWalletOrderAnalysisEntity> closedOrders = filterClosedRows(closedRows, normalizedWindow, cutoff);
        List<WalletOrderAggregationService.ActivePositionSnapshot> filteredActivePositions =
                filterActivePositions(activePositions, normalizedWindow, cutoff);

        int winCount = 0;
        int lossCount = 0;
        int settledCount = 0;
        int settledWins = 0;
        int earlySettlementCount = 0;
        BigDecimal closedRealizedPnlUsdc = BigDecimal.ZERO;
        BigDecimal activeUnrealizedPnlUsdc = BigDecimal.ZERO;
        BigDecimal totalRoi = BigDecimal.ZERO;
        int roiSamples = 0;
        int pendingSettlementCount = 0;
        int activePositionCount = 0;

        for (SmartWalletOrderAnalysisEntity order : closedOrders) {
            if (order == null) {
                continue;
            }
            BigDecimal realizedPnl = safe(order.getRealizedPnlUsdc());
            closedRealizedPnlUsdc = closedRealizedPnlUsdc.add(realizedPnl);
            if (realizedPnl.compareTo(BigDecimal.ZERO) > 0) {
                winCount++;
            } else if (realizedPnl.compareTo(BigDecimal.ZERO) < 0) {
                lossCount++;
            }
            if (isEarlySettlement(order)) {
                earlySettlementCount++;
            } else if (!"UNKNOWN".equalsIgnoreCase(defaultString(order.getExitStyle()))) {
                settledCount++;
                if (realizedPnl.compareTo(BigDecimal.ZERO) > 0) {
                    settledWins++;
                }
            }
            if (order.getRoiRate() != null) {
                totalRoi = totalRoi.add(order.getRoiRate());
                roiSamples++;
            }
        }

        for (WalletOrderAggregationService.ActivePositionSnapshot position : filteredActivePositions) {
            if (position == null) {
                continue;
            }
            if (Boolean.TRUE.equals(position.getPendingSettlement())) {
                pendingSettlementCount++;
                continue;
            }
            activePositionCount++;
            if (position.getUnrealizedPnlUsdc() != null) {
                activeUnrealizedPnlUsdc = activeUnrealizedPnlUsdc.add(position.getUnrealizedPnlUsdc());
            }
        }

        int closedOrderCount = closedOrders.size();
        int drawCount = closedOrderCount - winCount - lossCount;
        return WalletOrderStatsView.builder()
                .walletAddress(walletAddress)
                .window(normalizedWindow)
                .generatedAt(new Date())
                .closedOrderCount(closedOrderCount)
                .winCount(winCount)
                .lossCount(lossCount)
                .drawCount(drawCount)
                .closedWinRate(ratio(winCount, winCount + lossCount))
                .closedRealizedPnlUsdc(scale(closedRealizedPnlUsdc, 4))
                .activeUnrealizedPnlUsdc(scale(activeUnrealizedPnlUsdc, 4))
                .netPnlUsdc(scale(closedRealizedPnlUsdc.add(activeUnrealizedPnlUsdc), 4))
                .averageRoiRate(roiSamples == 0
                        ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                        : totalRoi.divide(BigDecimal.valueOf(roiSamples), 4, RoundingMode.HALF_UP))
                .activePositionCount(activePositionCount)
                .pendingSettlementCount(pendingSettlementCount)
                .earlySettlementRate(ratio(earlySettlementCount, closedOrderCount))
                .settlementWinRate(ratio(settledWins, settledCount))
                .build();
    }

    private List<WalletOrderAggregationService.ClosedOrderSnapshot> filterClosedOrders(
            List<WalletOrderAggregationService.ClosedOrderSnapshot> orders,
            String window,
            long cutoff) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        if ("ALL".equals(window)) {
            return orders;
        }
        List<WalletOrderAggregationService.ClosedOrderSnapshot> result = new ArrayList<>();
        for (WalletOrderAggregationService.ClosedOrderSnapshot order : orders) {
            if (order == null || order.getSettlementAt() == null) {
                continue;
            }
            if (order.getSettlementAt().getTime() >= cutoff) {
                result.add(order);
            }
        }
        return result;
    }

    private List<WalletOrderAggregationService.ActivePositionSnapshot> filterActivePositions(
            List<WalletOrderAggregationService.ActivePositionSnapshot> positions,
            String window,
            long cutoff) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        if ("ALL".equals(window)) {
            return positions;
        }
        List<WalletOrderAggregationService.ActivePositionSnapshot> result = new ArrayList<>();
        for (WalletOrderAggregationService.ActivePositionSnapshot position : positions) {
            if (position == null || position.getOpenedAt() == null) {
                continue;
            }
            if (position.getOpenedAt().getTime() >= cutoff) {
                result.add(position);
            }
        }
        return result;
    }

    private List<SmartWalletOrderAnalysisEntity> filterClosedRows(
            List<SmartWalletOrderAnalysisEntity> rows,
            String window,
            long cutoff) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        if ("ALL".equals(window)) {
            return rows;
        }
        List<SmartWalletOrderAnalysisEntity> result = new ArrayList<>();
        for (SmartWalletOrderAnalysisEntity row : rows) {
            if (row == null || row.getClosedAt() == null) {
                continue;
            }
            if (row.getClosedAt().getTime() >= cutoff) {
                result.add(row);
            }
        }
        return result;
    }

    private boolean isEarlySettlement(SmartWalletOrderAnalysisEntity order) {
        return order != null
                && order.getClosedAt() != null
                && order.getMarketEndAt() != null
                && order.getClosedAt().getTime() < order.getMarketEndAt();
    }

    private String normalizeWindow(String window) {
        if (!StringUtils.hasText(window)) {
            return "30D";
        }
        String normalized = window.trim().toUpperCase(Locale.ROOT);
        if ("7D".equals(normalized) || "30D".equals(normalized) || "ALL".equals(normalized)) {
            return normalized;
        }
        return "30D";
    }

    private long cutoffMillis(String window) {
        return switch (window) {
            case "7D" -> System.currentTimeMillis() - 7L * 24L * 3600_000L;
            case "30D" -> System.currentTimeMillis() - 30L * 24L * 3600_000L;
            default -> Long.MIN_VALUE;
        };
    }

    private BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
