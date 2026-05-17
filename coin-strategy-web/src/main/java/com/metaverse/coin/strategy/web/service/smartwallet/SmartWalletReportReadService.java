package com.metaverse.coin.strategy.web.service.smartwallet;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.*;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.*;
import com.metaverse.coin.strategy.web.model.smartwallet.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SmartWalletReportReadService {

    private static final String PAPER_BOOK = "paper-book";
    private static final List<String> ACTIVE_STATUSES = List.of("OPEN", "PARTIALLY_CLOSED");
    private static final List<String> CLOSED_STATUSES = List.of("CLOSED", "SETTLED", "SETTLEMENT_FAILED");
    private static final List<String> PENDING_SETTLEMENT_STATUSES = List.of("SETTLEMENT_PENDING_ORACLE", "RESOLVED_UNCLAIMED");
    private static final List<String> CAPITAL_LOCKED_STATUSES = List.of("OPEN", "PARTIALLY_CLOSED", "SETTLEMENT_PENDING_ORACLE", "RESOLVED_UNCLAIMED");

    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletFillMapper fillMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletCashLedgerMapper cashLedgerMapper;
    private final SmartWalletMarketInfoMapper marketInfoMapper;
    private final SmartWalletTradeOrderMapper tradeOrderMapper;

    @Value("${smart-wallet.signal-risk.max-open-positions:30}")
    private Integer maxOpenPositions;
    @Value("${smart-wallet.signal-risk.max-total-open-cost-usdc:5000}")
    private BigDecimal maxTotalOpenCostUsdc;
    @Value("${smart-wallet.signal-risk.max-hours-to-market-end:72}")
    private Integer maxHoursToMarketEnd;
    @Value("${smart-wallet.signal-risk.block-duplicate-open-position:true}")
    private Boolean blockDuplicateOpenPosition;
    @Value("${smart-wallet.signal-risk.block-duplicate-pending-task:true}")
    private Boolean blockDuplicatePendingTask;
    @Value("${POLYMARKET_EXECUTION_WALLET_ADDRESS:}")
    private String executionWalletAddress;

    public SmartWalletReportReadService(SmartWalletOrderMapper orderMapper,
                                         SmartWalletFillMapper fillMapper,
                                         SmartWalletPositionMapper positionMapper,
                                         SmartWalletCashLedgerMapper cashLedgerMapper,
                                         SmartWalletMarketInfoMapper marketInfoMapper,
                                         SmartWalletTradeOrderMapper tradeOrderMapper) {
        this.orderMapper = orderMapper;
        this.fillMapper = fillMapper;
        this.positionMapper = positionMapper;
        this.cashLedgerMapper = cashLedgerMapper;
        this.marketInfoMapper = marketInfoMapper;
        this.tradeOrderMapper = tradeOrderMapper;
    }

    // ════════════════════════════════════════════════════════════════
    // 1. PnL Summary
    // ════════════════════════════════════════════════════════════════
    public SmartWalletPnlSummaryView pnlSummary(String mode) {
        String normalizedMode = normalizeMode(mode);
        int totalOrders = orderMapper.countByMode(normalizedMode);
        int filledOrders = fillMapper.countByMode(normalizedMode);
        BigDecimal volume = safe(fillMapper.sumVolumeByMode(normalizedMode));
        BigDecimal fee = totalFee(normalizedMode);
        BigDecimal realized = safe(cashLedgerMapper.sumSignedAmountByMode("REALIZED_PNL", normalizedMode, null, null));

        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        int wins = 0, completed = 0, openCount = 0;
        BigDecimal unrealized = BigDecimal.ZERO, pendingSettlement = BigDecimal.ZERO;
        for (SmartWalletPositionEntity p : positions) {
            if (!matchesMode(p.getExecutionAddress(), mode)) continue;
            if (isPendingSettlement(p)) {
                pendingSettlement = pendingSettlement.add(pendingNetPnl(p));
            } else if (isActive(p)) {
                unrealized = unrealized.add(safe(p.getUnrealizedPnlUsdc()));
                openCount++;
            }
            if (isClosed(p)) {
                completed++;
                if (realizedPnl(p).compareTo(BigDecimal.ZERO) > 0) wins++;
            }
        }
        BigDecimal winRate = completed == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
        BigDecimal settledNet = realized.subtract(fee);
        BigDecimal composite = settledNet.add(unrealized).add(pendingSettlement);
        return new SmartWalletPnlSummaryView(totalOrders, filledOrders, openCount, volume, fee,
                realized, unrealized, pendingSettlement, composite, composite, winRate);
    }

    // ════════════════════════════════════════════════════════════════
    // 2. Performance Report
    // ════════════════════════════════════════════════════════════════
    public SmartWalletPerformanceView performance(String mode) {
        String normalizedMode = normalizeMode(mode);
        BigDecimal volume = safe(fillMapper.sumVolumeByMode(normalizedMode));
        BigDecimal fee = totalFee(normalizedMode);

        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletTradeOrderEntity> tradeOrders = tradeOrderMapper.selectList(new LambdaQueryWrapper<>());
        Date now = new Date();

        // 总览页面所有"订单 / 盈利订单 / 亏损订单 / 收益"统一以 smart_wallet_trade_order 为口径，
        // 让首页统计与"订单记录页(/trade-orders/page)"按 pnl 过滤出的条数严格一致。
        int totalOrders = 0, filledOrders = 0;
        int openPos = 0, pendingPos = 0, closedPos = 0;
        int winPos = 0, losePos = 0;                       // 按 total_pnl 符号统计：覆盖所有状态
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal pendingSettlement = BigDecimal.ZERO;
        BigDecimal realizedAsOfNow = BigDecimal.ZERO;
        BigDecimal totalWins = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;

        for (SmartWalletTradeOrderEntity order : tradeOrders) {
            if (!matchesMode(order.getExecutionAddress(), mode)) continue;

            totalOrders++;
            if (safe(order.getEntryQuantity()).compareTo(BigDecimal.ZERO) > 0) {
                filledOrders++;
            }

            BigDecimal pnl = safe(order.getTotalPnlUsdc());

            // 盈利/亏损订单数 == 订单记录页按 pnl 过滤的条数（任何状态都计入）
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                winPos++;
                totalWins = totalWins.add(pnl);
            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                losePos++;
                totalLosses = totalLosses.add(pnl.abs());
            }

            if (isOpenTradeOrder(order)) {
                openPos++;
                unrealized = unrealized.add(pnl);
            } else if (isFutureCompletedTradeOrder(order, now)) {
                // 已平/已结算但 settled_at / last_exit_at 还在未来：算待结算
                pendingPos++;
                pendingSettlement = pendingSettlement.add(pnl);
            } else {
                closedPos++;
                realizedAsOfNow = realizedAsOfNow.add(pnl);
            }
        }

        // Conflict tracking 仍然依赖 position 表的 outcome 信息。
        Map<String, List<SmartWalletPositionEntity>> byMarket = new LinkedHashMap<>();
        for (SmartWalletPositionEntity p : positions) {
            if (!matchesMode(p.getExecutionAddress(), mode)) continue;
            byMarket.computeIfAbsent(p.getMarketId(), k -> new ArrayList<>()).add(p);
        }
        // Conflict summary
        int conflictMarkets = 0, conflictPositions = 0, openConflictMarkets = 0, openConflictPositions = 0;
        BigDecimal conflictPnl = BigDecimal.ZERO;
        for (var entry : byMarket.entrySet()) {
            List<SmartWalletPositionEntity> posInMarket = entry.getValue();
            Set<String> outcomes = posInMarket.stream().map(SmartWalletPositionEntity::getOutcome)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (outcomes.size() > 1) {
                conflictMarkets++;
                conflictPositions += posInMarket.size();
                boolean hasOpen = posInMarket.stream().anyMatch(this::isActive);
                if (hasOpen) { openConflictMarkets++; openConflictPositions += (int) posInMarket.stream().filter(this::isActive).count(); }
                conflictPnl = conflictPnl.add(posInMarket.stream().map(this::realizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add));
            }
        }

        // Exit order summary
        int earlyExitCount = 0, takeProfitCount = 0, stopLossCount = 0;
        BigDecimal earlyExitPnl = BigDecimal.ZERO, takeProfitPnl = BigDecimal.ZERO, stopLossPnl = BigDecimal.ZERO;
        for (SmartWalletOrderEntity order : orders) {
            if (!matchesMode(order.getExecutionAddress(), mode)) continue;
            String exitReason = order.getExitReason();
            if (!StringUtils.hasText(exitReason)) continue;
            BigDecimal orderPnl = safe(order.getRealizedPnlUsdc());
            if (exitReason.contains("STOP_LOSS")) { stopLossCount++; stopLossPnl = stopLossPnl.add(orderPnl); }
            else if (exitReason.contains("TAKE_PROFIT")) { takeProfitCount++; takeProfitPnl = takeProfitPnl.add(orderPnl); }
            else if (exitReason.contains("EARLY") || exitReason.contains("LEADER_EXIT") || exitReason.contains("MANUAL")) {
                earlyExitCount++; earlyExitPnl = earlyExitPnl.add(orderPnl);
            }
        }

        int completed = winPos + losePos;
        BigDecimal winRate = completed == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(winPos).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
        BigDecimal avgWin = winPos == 0 ? BigDecimal.ZERO : totalWins.divide(BigDecimal.valueOf(winPos), 8, RoundingMode.HALF_UP);
        BigDecimal avgLoss = losePos == 0 ? BigDecimal.ZERO : totalLosses.divide(BigDecimal.valueOf(losePos), 8, RoundingMode.HALF_UP);
        BigDecimal profitFactor = totalLosses.compareTo(BigDecimal.ZERO) == 0 ? totalWins
                : totalWins.divide(totalLosses, 8, RoundingMode.HALF_UP);
        // 总收益 == 所有 trade_order 的 total_pnl_usdc 之和（= realized + unrealized + pending settlement）。
        BigDecimal composite = realizedAsOfNow.add(unrealized).add(pendingSettlement);

        return new SmartWalletPerformanceView(totalOrders, filledOrders, closedPos, openPos, pendingPos,
                winPos, losePos, winRate, volume, fee, realizedAsOfNow, unrealized, pendingSettlement, composite, composite,
                avgWin, avgLoss, profitFactor, BigDecimal.ZERO, // maxDrawdown simplified
                conflictMarkets, conflictPositions, openConflictMarkets, openConflictPositions, conflictPnl,
                earlyExitCount, earlyExitPnl, takeProfitCount, takeProfitPnl, stopLossCount, stopLossPnl);
    }

    // ════════════════════════════════════════════════════════════════
    // 3. Follow Address Daily Report
    // ════════════════════════════════════════════════════════════════
    public List<SmartWalletFollowAddressDailyReportView> followAddressDailyReport(LocalDate reportDate, int limit, String mode) {
        LocalDate targetDate = reportDate == null ? LocalDate.now(ZoneId.systemDefault()) : reportDate;
        Date start = Date.from(targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date end = Date.from(targetDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        boolean currentDay = targetDate.equals(LocalDate.now(ZoneId.systemDefault()));

        // Query orders placed on target date
        List<SmartWalletOrderEntity> dayOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .ge(SmartWalletOrderEntity::getPlacedAt, start)
                .lt(SmartWalletOrderEntity::getPlacedAt, end)
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt));
        // Query fills on target date
        List<SmartWalletFillEntity> dayFills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .ge(SmartWalletFillEntity::getFilledAt, start)
                .lt(SmartWalletFillEntity::getFilledAt, end)
                .orderByDesc(SmartWalletFillEntity::getFilledAt));
        // Query positions closed on target date + open positions for current day
        List<SmartWalletPositionEntity> positions;
        if (currentDay) {
            positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .and(w -> w.in(SmartWalletPositionEntity::getStatus, ACTIVE_STATUSES)
                            .or().ge(SmartWalletPositionEntity::getClosedAt, start)));
        } else {
            positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .ge(SmartWalletPositionEntity::getClosedAt, start)
                    .lt(SmartWalletPositionEntity::getClosedAt, end));
        }

        // Group by wallet address
        Map<String, DailyAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : dayOrders) {
            if (!matchesMode(order.getExecutionAddress(), mode)) continue;
            String wallet = normalizeWallet(order.getWalletAddress());
            grouped.computeIfAbsent(wallet, DailyAccumulator::new).addOrder(order);
        }
        for (SmartWalletFillEntity fill : dayFills) {
            if (!matchesMode(fill.getExecutionAddress(), mode)) continue;
            String wallet = normalizeWallet(fill.getWalletAddress());
            grouped.computeIfAbsent(wallet, DailyAccumulator::new).addFill(fill);
        }
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesMode(position.getExecutionAddress(), mode)) continue;
            // Resolve wallet from order's walletAddress via position's marketId+outcome
            String wallet = resolvePositionWallet(position, dayOrders);
            DailyAccumulator acc = grouped.computeIfAbsent(wallet, DailyAccumulator::new);
            if (position.getClosedAt() != null && isWithin(position.getClosedAt(), start, end)) {
                acc.addClosedPosition(position);
            }
            if (currentDay && isActive(position)) {
                acc.addOpenPosition(position);
            }
        }

        return grouped.values().stream()
                .sorted(Comparator.comparing(DailyAccumulator::netPnl).reversed())
                .limit(Math.max(limit, 1))
                .map(acc -> acc.toView(java.sql.Date.valueOf(targetDate)))
                .toList();
    }

    // ════════════════════════════════════════════════════════════════
    // 4. Capacity Summary
    // ════════════════════════════════════════════════════════════════
    public SmartWalletCapacitySummaryView capacitySummary(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(
                new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .in(SmartWalletPositionEntity::getStatus, CAPITAL_LOCKED_STATUSES));
        BigDecimal totalOpenCost = BigDecimal.ZERO;
        int openCount = 0;
        BigDecimal lockedClaims = BigDecimal.ZERO;
        for (SmartWalletPositionEntity p : positions) {
            if (!matchesMode(p.getExecutionAddress(), mode)) continue;
            openCount++;
            totalOpenCost = totalOpenCost.add(safe(p.getCostBasisUsdc()));
            lockedClaims = lockedClaims.add(lockedClaimProceeds(p));
        }
        String normalizedMode = normalizeMode(mode);
        BigDecimal realized = safe(cashLedgerMapper.sumSignedAmountByMode("REALIZED_PNL", normalizedMode, null, null));
        BigDecimal fees = totalFee(normalizedMode);
        BigDecimal budget = safe(maxTotalOpenCostUsdc);
        BigDecimal available = budget.add(realized).subtract(fees).subtract(totalOpenCost).subtract(lockedClaims)
                .setScale(8, RoundingMode.HALF_UP);
        int resolvedLimit = maxOpenPositions == null || maxOpenPositions <= 0 ? Integer.MAX_VALUE : maxOpenPositions;
        return new SmartWalletCapacitySummaryView(openCount, maxOpenPositions,
                Math.max(0, resolvedLimit == Integer.MAX_VALUE ? 0 : resolvedLimit - openCount),
                totalOpenCost, maxTotalOpenCostUsdc,
                available.max(BigDecimal.ZERO), available, lockedClaims,
                maxHoursToMarketEnd, blockDuplicateOpenPosition, blockDuplicatePendingTask);
    }

    // ════════════════════════════════════════════════════════════════
    // Helper methods
    // ════════════════════════════════════════════════════════════════
    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) return null;
        return mode.trim().toUpperCase();
    }

    private boolean matchesMode(String executionAddress, String mode) {
        String normalized = normalizeMode(mode);
        if (normalized == null) return true;
        return "PAPER".equals(normalized)
                ? isPaperAddress(executionAddress)
                : !isPaperAddress(executionAddress) && StringUtils.hasText(executionAddress);
    }

    private boolean isPaperAddress(String address) {
        return !StringUtils.hasText(address) || PAPER_BOOK.equals(address);
    }

    private boolean isActive(SmartWalletPositionEntity p) {
        return p != null && ACTIVE_STATUSES.contains(p.getStatus());
    }

    private boolean isClosed(SmartWalletPositionEntity p) {
        return p != null && CLOSED_STATUSES.contains(p.getStatus());
    }

    private boolean isPendingSettlement(SmartWalletPositionEntity p) {
        return p != null && PENDING_SETTLEMENT_STATUSES.contains(p.getStatus());
    }

    private boolean isOpenTradeOrder(SmartWalletTradeOrderEntity order) {
        return order != null && "OPEN".equalsIgnoreCase(order.getStatus());
    }

    private boolean isFutureCompletedTradeOrder(SmartWalletTradeOrderEntity order, Date now) {
        if (order == null || isOpenTradeOrder(order) || now == null) {
            return false;
        }
        Date outcomeAt = order.getSettledAt() != null ? order.getSettledAt() : order.getLastExitAt();
        return outcomeAt != null && outcomeAt.after(now);
    }

    private BigDecimal realizedPnl(SmartWalletPositionEntity p) {
        return safe(p.getRealizedPnlUsdc());
    }

    private BigDecimal pendingNetPnl(SmartWalletPositionEntity p) {
        // For pending settlement: claimable - cost = expected net
        BigDecimal claimable = safe(p.getClaimableProceedsUsdc());
        BigDecimal cost = safe(p.getCostBasisUsdc());
        return claimable.subtract(cost);
    }

    private BigDecimal lockedClaimProceeds(SmartWalletPositionEntity p) {
        if (PENDING_SETTLEMENT_STATUSES.contains(p.getStatus())) {
            return safe(p.getClaimableProceedsUsdc());
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal totalFee(String normalizedMode) {
        // 数据库 entry_type 的实际取值为 "FEE" / "NETWORK_FEE"，历史代码错写 "TRADING_FEE"/"GAS_FEE"
        // 导致总览页面"已实现"和净收益总把手续费当作 0，统计不准。
        BigDecimal tradingFee = safe(cashLedgerMapper.sumAmountByMode("FEE", normalizedMode, null, null));
        BigDecimal networkFee = safe(cashLedgerMapper.sumAmountByMode("NETWORK_FEE", normalizedMode, null, null));
        return tradingFee.add(networkFee);
    }

    private BigDecimal safe(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private String normalizeWallet(String addr) { return StringUtils.hasText(addr) ? addr.toLowerCase() : "unknown"; }

    private boolean isWithin(Date d, Date start, Date end) {
        return d != null && !d.before(start) && d.before(end);
    }

    private String resolvePositionWallet(SmartWalletPositionEntity position, List<SmartWalletOrderEntity> orders) {
        // Try to find wallet from order matching position's marketId+outcome
        for (SmartWalletOrderEntity order : orders) {
            if (Objects.equals(order.getMarketId(), position.getMarketId())
                    && Objects.equals(order.getOutcome(), position.getOutcome())
                    && Objects.equals(order.getExecutionAddress(), position.getExecutionAddress())) {
                if (StringUtils.hasText(order.getWalletAddress())) {
                    return normalizeWallet(order.getWalletAddress());
                }
            }
        }
        return normalizeWallet(position.getExecutionAddress());
    }

    // ════════════════════════════════════════════════════════════════
    // DailyAccumulator inner class
    // ════════════════════════════════════════════════════════════════
    private static class DailyAccumulator {
        private final String walletAddress;
        private int totalOrders, filledOrders, openPositions, closedPositions;
        private int winningPositions, losingPositions, breakevenPositions;
        private final Set<String> distinctMarkets = new LinkedHashSet<>();
        private BigDecimal tradedVolume = BigDecimal.ZERO, grossExposure = BigDecimal.ZERO, fee = BigDecimal.ZERO;
        private BigDecimal realizedPnl = BigDecimal.ZERO, unrealizedPnl = BigDecimal.ZERO;
        private BigDecimal totalWins = BigDecimal.ZERO, totalLosses = BigDecimal.ZERO;
        private BigDecimal largestWin = BigDecimal.ZERO, largestLoss = BigDecimal.ZERO;

        DailyAccumulator(String walletAddress) { this.walletAddress = walletAddress; }

        void addOrder(SmartWalletOrderEntity o) {
            totalOrders++;
            if (o.getMarketId() != null) distinctMarkets.add(o.getMarketId());
        }

        void addFill(SmartWalletFillEntity f) {
            filledOrders++;
            BigDecimal notional = f.getNotionalUsdc() != null ? f.getNotionalUsdc() : BigDecimal.ZERO;
            tradedVolume = tradedVolume.add(notional);
            BigDecimal fillFee = f.getFeeUsdc() != null ? f.getFeeUsdc() : BigDecimal.ZERO;
            fee = fee.add(fillFee);
        }

        void addClosedPosition(SmartWalletPositionEntity p) {
            closedPositions++;
            BigDecimal pnl = p.getRealizedPnlUsdc() != null ? p.getRealizedPnlUsdc() : BigDecimal.ZERO;
            realizedPnl = realizedPnl.add(pnl);
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                winningPositions++;
                totalWins = totalWins.add(pnl);
                if (pnl.compareTo(largestWin) > 0) largestWin = pnl;
            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                losingPositions++;
                totalLosses = totalLosses.add(pnl.abs());
                if (pnl.abs().compareTo(largestLoss) > 0) largestLoss = pnl.abs();
            } else {
                breakevenPositions++;
            }
        }

        void addOpenPosition(SmartWalletPositionEntity p) {
            openPositions++;
            BigDecimal cost = p.getCostBasisUsdc() != null ? p.getCostBasisUsdc() : BigDecimal.ZERO;
            grossExposure = grossExposure.add(cost);
            BigDecimal uPnl = p.getUnrealizedPnlUsdc() != null ? p.getUnrealizedPnlUsdc() : BigDecimal.ZERO;
            unrealizedPnl = unrealizedPnl.add(uPnl);
        }

        BigDecimal netPnl() { return realizedPnl.add(unrealizedPnl).subtract(fee); }

        SmartWalletFollowAddressDailyReportView toView(Date reportDate) {
            int completed = winningPositions + losingPositions;
            BigDecimal winRate = completed == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(winningPositions).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
            BigDecimal avgWin = winningPositions == 0 ? BigDecimal.ZERO
                    : totalWins.divide(BigDecimal.valueOf(winningPositions), 8, RoundingMode.HALF_UP);
            BigDecimal avgLoss = losingPositions == 0 ? BigDecimal.ZERO
                    : totalLosses.divide(BigDecimal.valueOf(losingPositions), 8, RoundingMode.HALF_UP);
            return new SmartWalletFollowAddressDailyReportView(reportDate, walletAddress,
                    totalOrders, filledOrders, openPositions, closedPositions,
                    winningPositions, losingPositions, breakevenPositions,
                    distinctMarkets.size(), tradedVolume, grossExposure, fee,
                    realizedPnl, unrealizedPnl, netPnl(), winRate, avgWin, avgLoss, largestWin, largestLoss);
        }
    }
}
