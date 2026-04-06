package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletDailyReportMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletCashLedgerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletDailyReportEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCashLedgerView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletCapacitySummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletConflictMarketView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFillView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReportAttributionItem;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairItem;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncFailure;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletAccountingService {

    private final SmartWalletOrderMapper orderMapper;
    private final SmartWalletFillMapper fillMapper;
    private final SmartWalletPositionMapper positionMapper;
    private final SmartWalletCashLedgerMapper cashLedgerMapper;
    private final SmartWalletDailyReportMapper dailyReportMapper;
    private final SmartWalletTradeEventMapper tradeEventMapper;
    private final SmartWalletFollowTaskMapper followTaskMapper;
    private final PolymarketMarketMetadataService marketMetadataService;
    private final SmartWalletTimeFormatter timeFormatter;
    private final com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties properties;

    public void recordExecution(SmartWalletFollowTaskEntity task, SmartWalletExecutionResult result) {
        Date now = task.getExecutedAt() == null ? new Date() : task.getExecutedAt();
        BigDecimal price = safe(result.getPrice(), task.getTriggerPrice());
        BigDecimal notional = safe(result.getNotionalUsdc(), task.getRecommendedStakeUsdc());
        BigDecimal size = safe(result.getSize(), deriveSize(notional, price));
        BigDecimal fee = safe(result.getFeeUsdc(), BigDecimal.ZERO);
        String executionAddress = StringUtils.hasText(result.getExecutionAddress()) ? result.getExecutionAddress() : "paper-book";
        String externalOrderId = StringUtils.hasText(result.getExternalOrderId()) ? result.getExternalOrderId() : "local-" + task.getId();
        String status = StringUtils.hasText(result.getStatus()) ? result.getStatus() : (result.isSuccess() ? "FILLED" : "FAILED");

        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setFollowTaskId(task.getId())
                .setWalletAddress(task.getWalletAddress())
                .setExecutionMode(task.getExecutionMode())
                .setExecutionAddress(executionAddress)
                .setExternalOrderId(externalOrderId)
                .setMarketId(task.getMarketId())
                .setTokenId(result.getTokenId())
                .setQuestion(task.getQuestion())
                .setOutcome(task.getOutcome())
                .setSide(StringUtils.hasText(result.getSide()) ? result.getSide() : task.getSide())
                .setOrderType(StringUtils.hasText(result.getOrderType()) ? result.getOrderType() : "GTC")
                .setStatus(status)
                .setPrice(price)
                .setSize(size)
                .setNotionalUsdc(notional)
                .setFeeUsdc(fee)
                .setErrorMessage(result.isSuccess() ? null : result.getMessage())
                .setPlacedAt(now)
                .setFilledAt(result.isSuccess() ? now : null)
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        orderMapper.insert(order);

        if (!result.isSuccess()) {
            rebuildDailyReport(executionAddress, toLocalDate(now));
            return;
        }

        SmartWalletFillEntity fill = new SmartWalletFillEntity()
                .setOrderId(order.getId())
                .setFollowTaskId(task.getId())
                .setWalletAddress(task.getWalletAddress())
                .setExecutionAddress(executionAddress)
                .setMarketId(task.getMarketId())
                .setTokenId(result.getTokenId())
                .setQuestion(task.getQuestion())
                .setOutcome(task.getOutcome())
                .setSide(order.getSide())
                .setPrice(price)
                .setSize(size)
                .setNotionalUsdc(notional)
                .setFeeUsdc(fee)
                .setFilledAt(now)
                .setCreatedAt(new Date());
        fillMapper.insert(fill);

        PositionUpdateResult positionUpdate = applyFillToPosition(fill);
        appendCashLedger(order, fill, positionUpdate);
        rebuildDailyReport(executionAddress, toLocalDate(now));
    }

    public List<SmartWalletOrderView> listOrders(int limit) {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .last("limit " + Math.max(limit, 1)));
        List<SmartWalletOrderView> result = new ArrayList<>();
        for (SmartWalletOrderEntity order : orders) {
            result.add(toOrderView(order));
        }
        return result;
    }

    public List<SmartWalletFillView> listFills(int limit) {
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .orderByDesc(SmartWalletFillEntity::getFilledAt)
                .last("limit " + Math.max(limit, 1)));
        List<SmartWalletFillView> result = new ArrayList<>();
        for (SmartWalletFillEntity fill : fills) {
            result.add(toFillView(fill));
        }
        return result;
    }

    public List<SmartWalletPositionView> listPositions() {
        return listPositions(false);
    }

    public List<SmartWalletPositionView> listPositions(boolean closedOnly) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt));
        if (closedOnly) {
            positions = positions.stream()
                    .filter(position -> "CLOSED".equalsIgnoreCase(position.getStatus()) || "RESOLVED".equalsIgnoreCase(position.getStatus()))
                    .toList();
        }
        for (SmartWalletPositionEntity position : positions) {
            markToMarket(position);
        }
        List<SmartWalletPositionView> result = new ArrayList<>();
        for (SmartWalletPositionEntity position : positions) {
            result.add(toPositionView(position));
        }
        return result;
    }

    public List<SmartWalletPositionView> listNextSettlements(int limit) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"))
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt));
        for (SmartWalletPositionEntity position : positions) {
            markToMarket(position);
        }
        List<SmartWalletPositionView> result = new ArrayList<>();
        for (SmartWalletPositionEntity position : positions) {
            result.add(toPositionView(position));
        }
        result.sort((left, right) -> {
            Long leftEnd = left.getMarketEndAt();
            Long rightEnd = right.getMarketEndAt();
            if (leftEnd == null && rightEnd == null) {
                return 0;
            }
            if (leftEnd == null) {
                return 1;
            }
            if (rightEnd == null) {
                return -1;
            }
            return Long.compare(leftEnd, rightEnd);
        });
        int safeLimit = Math.max(limit, 1);
        return result.size() <= safeLimit ? result : new ArrayList<>(result.subList(0, safeLimit));
    }

    public List<SmartWalletCashLedgerView> listLedger(int limit) {
        List<SmartWalletCashLedgerEntity> items = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .orderByDesc(SmartWalletCashLedgerEntity::getOccurredAt)
                .last("limit " + Math.max(limit, 1)));
        List<SmartWalletCashLedgerView> result = new ArrayList<>();
        for (SmartWalletCashLedgerEntity item : items) {
            result.add(toLedgerView(item));
        }
        return result;
    }

    public SmartWalletPnlSummaryView summary() {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        int filledOrders = 0;
        int wins = 0;
        int openPositions = 0;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (SmartWalletOrderEntity order : orders) {
            volume = volume.add(safe(order.getNotionalUsdc()));
            fee = fee.add(safe(order.getFeeUsdc()));
            if ("FILLED".equalsIgnoreCase(order.getStatus())) {
                filledOrders++;
            }
        }
        for (SmartWalletPositionEntity position : positions) {
            markToMarket(position);
            realized = realized.add(safe(position.getRealizedPnlUsdc()));
            unrealized = unrealized.add(safe(position.getUnrealizedPnlUsdc()));
            if ("OPEN".equalsIgnoreCase(position.getStatus())) {
                openPositions++;
            }
            if (safe(position.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) > 0) {
                wins++;
            }
        }
        BigDecimal winRate = positions.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(positions.size()), 4, RoundingMode.HALF_UP);
        return SmartWalletPnlSummaryView.builder()
                .totalOrders(orders.size())
                .filledOrders(filledOrders)
                .openPositions(openPositions)
                .tradedVolumeUsdc(volume)
                .feeUsdc(fee)
                .realizedPnlUsdc(realized)
                .unrealizedPnlUsdc(unrealized)
                .netPnlUsdc(realized.add(unrealized).subtract(fee))
                .winRate(winRate)
                .build();
    }

    public SmartWalletCapacitySummaryView capacitySummary() {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        int openPositionCount = positions.size();
        BigDecimal totalOpenCost = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            totalOpenCost = totalOpenCost.add(safe(position.getCostBasisUsdc()));
        }

        Integer openPositionLimit = properties.getSignalRisk().getMaxOpenPositions();
        BigDecimal openCostLimit = properties.getSignalRisk().getMaxTotalOpenCostUsdc();
        int resolvedPositionLimit = openPositionLimit == null || openPositionLimit <= 0 ? Integer.MAX_VALUE : openPositionLimit;
        BigDecimal resolvedCostLimit = openCostLimit == null ? BigDecimal.ZERO : openCostLimit;

        return SmartWalletCapacitySummaryView.builder()
                .openPositionCount(openPositionCount)
                .openPositionLimit(openPositionLimit)
                .remainingPositionSlots(Math.max(0, resolvedPositionLimit == Integer.MAX_VALUE ? 0 : resolvedPositionLimit - openPositionCount))
                .totalOpenCostUsdc(totalOpenCost)
                .openCostLimitUsdc(openCostLimit)
                .remainingCapitalBudgetUsdc(resolvedCostLimit.subtract(totalOpenCost).max(BigDecimal.ZERO))
                .maxHoursToMarketEnd(properties.getSignalRisk().getMaxHoursToMarketEnd())
                .duplicateOpenPositionBlocked(properties.getSignalRisk().isBlockDuplicateOpenPosition())
                .duplicatePendingTaskBlocked(properties.getSignalRisk().isBlockDuplicatePendingTask())
                .build();
    }

    public List<SmartWalletConflictMarketView> conflictingMarkets(int limit, boolean openOnly) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt));
        Map<String, SmartWalletConflictMarketAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletPositionEntity position : positions) {
            if (openOnly && !"OPEN".equalsIgnoreCase(position.getStatus()) && !"PARTIALLY_CLOSED".equalsIgnoreCase(position.getStatus())) {
                continue;
            }
            if (!StringUtils.hasText(position.getMarketId())) {
                continue;
            }
            grouped.computeIfAbsent(position.getMarketId(),
                            key -> new SmartWalletConflictMarketAccumulator(position.getMarketId(), position.getQuestion()))
                    .add(position);
        }
        List<SmartWalletConflictMarketView> result = new ArrayList<>();
        for (SmartWalletConflictMarketAccumulator item : grouped.values()) {
            if (item.getOutcomes().size() < 2) {
                continue;
            }
            Long marketEndAt = resolveMarketEndAt(item.getMarketId());
            result.add(item.toView(marketEndAt, timeFormatter.formatEpochMillisToBjt(marketEndAt)));
        }
        result.sort((left, right) -> right.getNetPnlUsdc().compareTo(left.getNetPnlUsdc()));
        if (result.size() > Math.max(limit, 1)) {
            return new ArrayList<>(result.subList(0, Math.max(limit, 1)));
        }
        return result;
    }

    public SmartWalletPerformanceView performance() {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        ConflictSummary conflictSummary = summarizeConflicts(positions, null);
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        int filledOrders = 0;
        int openPositions = 0;
        int closedPositions = 0;
        int winningPositions = 0;
        int losingPositions = 0;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal totalWins = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;
        for (SmartWalletOrderEntity order : orders) {
            volume = volume.add(safe(order.getNotionalUsdc()));
            fee = fee.add(safe(order.getFeeUsdc()));
            if ("FILLED".equalsIgnoreCase(order.getStatus())) {
                filledOrders++;
            }
        }
        for (SmartWalletPositionEntity position : positions) {
            markToMarket(position);
            BigDecimal positionRealized = safe(position.getRealizedPnlUsdc());
            BigDecimal positionUnrealized = safe(position.getUnrealizedPnlUsdc());
            realized = realized.add(positionRealized);
            unrealized = unrealized.add(positionUnrealized);
            if ("CLOSED".equalsIgnoreCase(position.getStatus()) || "RESOLVED".equalsIgnoreCase(position.getStatus())) {
                closedPositions++;
            } else {
                openPositions++;
            }
            if (positionRealized.compareTo(BigDecimal.ZERO) > 0) {
                winningPositions++;
                totalWins = totalWins.add(positionRealized);
            }
            if (positionRealized.compareTo(BigDecimal.ZERO) < 0) {
                losingPositions++;
                totalLosses = totalLosses.add(positionRealized.abs());
            }
        }
        int completed = winningPositions + losingPositions;
        BigDecimal winRate = completed == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(winningPositions).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
        BigDecimal averageWin = winningPositions == 0 ? BigDecimal.ZERO
                : totalWins.divide(BigDecimal.valueOf(winningPositions), 8, RoundingMode.HALF_UP);
        BigDecimal averageLoss = losingPositions == 0 ? BigDecimal.ZERO
                : totalLosses.divide(BigDecimal.valueOf(losingPositions), 8, RoundingMode.HALF_UP);
        BigDecimal profitFactor = totalLosses.compareTo(BigDecimal.ZERO) == 0
                ? totalWins
                : totalWins.divide(totalLosses, 8, RoundingMode.HALF_UP);
        BigDecimal maxDrawdown = calculateMaxDrawdown();
        return SmartWalletPerformanceView.builder()
                .totalOrders(orders.size())
                .filledOrders(filledOrders)
                .closedPositions(closedPositions)
                .openPositions(openPositions)
                .winningPositions(winningPositions)
                .losingPositions(losingPositions)
                .winRate(winRate)
                .tradedVolumeUsdc(volume)
                .feeUsdc(fee)
                .realizedPnlUsdc(realized)
                .unrealizedPnlUsdc(unrealized)
                .netPnlUsdc(realized.add(unrealized).subtract(fee))
                .averageWinUsdc(averageWin)
                .averageLossUsdc(averageLoss)
                .profitFactor(profitFactor)
                .maxDrawdownUsdc(maxDrawdown)
                .conflictingMarkets(conflictSummary.conflictingMarkets())
                .conflictingPositions(conflictSummary.conflictingPositions())
                .openConflictingMarkets(conflictSummary.openConflictingMarkets())
                .openConflictingPositions(conflictSummary.openConflictingPositions())
                .conflictNetPnlUsdc(conflictSummary.conflictNetPnlUsdc())
                .build();
    }

    public List<SmartWalletPnlTimelinePoint> pnlTimeline(int days) {
        int safeDays = Math.max(days, 1);
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByAsc(SmartWalletDailyReportEntity::getReportDate)
                .last("limit " + safeDays));
        List<SmartWalletPnlTimelinePoint> points = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (SmartWalletDailyReportEntity report : reports) {
            BigDecimal realized = safe(report.getRealizedPnlUsdc());
            BigDecimal fee = safe(report.getFeeUsdc());
            BigDecimal net = safe(report.getNetPnlUsdc(), realized.subtract(fee));
            cumulative = cumulative.add(net);
            points.add(SmartWalletPnlTimelinePoint.builder()
                    .bucketAt(report.getReportDate())
                    .realizedPnlUsdc(realized)
                    .feeUsdc(fee)
                    .netPnlUsdc(net)
                    .cumulativeNetPnlUsdc(cumulative)
                    .build());
        }
        return points;
    }

    public List<SmartWalletDailyReportView> listDailyReports(int limit) {
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByDesc(SmartWalletDailyReportEntity::getReportDate)
                .last("limit " + Math.max(limit, 1)));
        List<SmartWalletDailyReportView> result = new ArrayList<>();
        for (SmartWalletDailyReportEntity report : reports) {
            result.add(toDailyReportView(report));
        }
        return result;
    }

    public List<SmartWalletReportAttributionItem> walletAttribution(int limit) {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getStatus, "FILLED"));
        Map<String, AttributionAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : orders) {
            AttributionAccumulator acc = grouped.computeIfAbsent(order.getWalletAddress(),
                    key -> new AttributionAccumulator(key, key));
            acc.addOrder(safe(order.getNotionalUsdc()), safe(order.getFeeUsdc()));
        }
        for (SmartWalletPositionEntity position : positionMapper.selectList(new LambdaQueryWrapper<>())) {
            AttributionAccumulator acc = grouped.computeIfAbsent(position.getExecutionAddress(),
                    key -> new AttributionAccumulator(key, key));
            acc.addPnl(safe(position.getRealizedPnlUsdc()), safe(position.getUnrealizedPnlUsdc()));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(AttributionAccumulator::netPnl).reversed())
                .limit(Math.max(limit, 1))
                .map(AttributionAccumulator::toView)
                .toList();
    }

    public List<SmartWalletReportAttributionItem> marketAttribution(int limit) {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getStatus, "FILLED"));
        Map<String, AttributionAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : orders) {
            String key = order.getMarketId();
            AttributionAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new AttributionAccumulator(key, order.getQuestion()));
            acc.addOrder(safe(order.getNotionalUsdc()), safe(order.getFeeUsdc()));
        }
        for (SmartWalletPositionEntity position : positionMapper.selectList(new LambdaQueryWrapper<>())) {
            AttributionAccumulator acc = grouped.computeIfAbsent(position.getMarketId(),
                    ignored -> new AttributionAccumulator(position.getMarketId(), position.getQuestion()));
            acc.addPnl(safe(position.getRealizedPnlUsdc()), safe(position.getUnrealizedPnlUsdc()));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(AttributionAccumulator::netPnl).reversed())
                .limit(Math.max(limit, 1))
                .map(AttributionAccumulator::toView)
                .toList();
    }

    public int rebuildRecentReports(int days) {
        int count = 0;
        String executionAddress = latestExecutionAddress();
        for (int i = 0; i < Math.max(days, 1); i++) {
            rebuildDailyReport(executionAddress, LocalDate.now().minusDays(i));
            count++;
        }
        return count;
    }

    public int backfillFromFollowTasks(int limit) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .in(SmartWalletFollowTaskEntity::getStatus, List.of("FOLLOWED", "FAILED"))
                .orderByAsc(SmartWalletFollowTaskEntity::getExecutedAt)
                .last("limit " + Math.max(limit, 1)));
        int imported = 0;
        for (SmartWalletFollowTaskEntity task : tasks) {
            Long count = orderMapper.selectCount(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                    .eq(SmartWalletOrderEntity::getFollowTaskId, task.getId()));
            if (count != null && count > 0) {
                continue;
            }
            recordExecution(task, SmartWalletExecutionResult.builder()
                    .signalId(String.valueOf(task.getId()))
                    .mode("LIVE".equalsIgnoreCase(task.getExecutionMode()) ? com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode.LIVE : com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode.PAPER)
                    .success("FOLLOWED".equalsIgnoreCase(task.getStatus()))
                    .message(task.getErrorMessage())
                    .externalOrderId(task.getExternalOrderId())
                    .executionAddress("LIVE".equalsIgnoreCase(task.getExecutionMode()) ? "live-wallet" : "paper-book")
                    .marketId(task.getMarketId())
                    .outcome(task.getOutcome())
                    .side(task.getSide())
                    .orderType("GTC")
                    .status("FOLLOWED".equalsIgnoreCase(task.getStatus()) ? "FILLED" : "FAILED")
                    .price(task.getTriggerPrice())
                    .notionalUsdc(task.getRecommendedStakeUsdc())
                    .feeUsdc(BigDecimal.ZERO)
                    .build());
            imported++;
        }
        return imported;
    }

    public PrematureSettlementRepairSummary repairPrematureResolvedPaperPositions(int limit, boolean refreshCache) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED")
                .orderByDesc(SmartWalletPositionEntity::getClosedAt)
                .last("limit " + Math.max(limit, 1)));
        int matched = 0;
        List<Long> repairedIds = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (SmartWalletPositionEntity position : positions) {
            if (!isPrematureSettlement(position, refreshCache, notes)) {
                continue;
            }
            matched++;
            if (repairPrematureSettlement(position, notes)) {
                repairedIds.add(position.getId());
            }
        }
        return PrematureSettlementRepairSummary.builder()
                .scannedPositions(positions.size())
                .matchedPositions(matched)
                .repairedPositions(repairedIds.size())
                .repairedPositionIds(repairedIds)
                .notes(notes.size() <= 20 ? notes : new ArrayList<>(notes.subList(0, 20)))
                .build();
    }

    public PrematureSettlementRepairSummary repairPrematureResolvedPaperPositionsByIds(List<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return PrematureSettlementRepairSummary.builder()
                    .scannedPositions(0)
                    .matchedPositions(0)
                    .repairedPositions(0)
                    .notes(List.of("No position ids were provided."))
                    .build();
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectBatchIds(positionIds).stream()
                .filter(position -> position != null
                        && "paper-book".equalsIgnoreCase(position.getExecutionAddress())
                        && "RESOLVED".equalsIgnoreCase(position.getStatus()))
                .toList();
        List<Long> repairedIds = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (SmartWalletPositionEntity position : positions) {
            if (repairPrematureSettlement(position, notes)) {
                repairedIds.add(position.getId());
            }
        }
        return PrematureSettlementRepairSummary.builder()
                .scannedPositions(positionIds.size())
                .matchedPositions(positions.size())
                .repairedPositions(repairedIds.size())
                .repairedPositionIds(repairedIds)
                .notes(notes.size() <= 20 ? notes : new ArrayList<>(notes.subList(0, 20)))
                .build();
    }

    public PrematureSettlementRepairSummary repairPrematureResolvedPaperPositionsByItems(List<PrematureSettlementRepairItem> items) {
        if (items == null || items.isEmpty()) {
            return PrematureSettlementRepairSummary.builder()
                    .scannedPositions(0)
                    .matchedPositions(0)
                    .repairedPositions(0)
                    .notes(List.of("No market/outcome items were provided."))
                    .build();
        }
        List<Long> repairedIds = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int matched = 0;
        for (PrematureSettlementRepairItem item : items) {
            if (item == null || !StringUtils.hasText(item.getMarketId()) || !StringUtils.hasText(item.getOutcome())) {
                continue;
            }
            List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                    .eq(SmartWalletPositionEntity::getStatus, "RESOLVED")
                    .eq(SmartWalletPositionEntity::getMarketId, item.getMarketId())
                    .eq(SmartWalletPositionEntity::getOutcome, item.getOutcome()));
            for (SmartWalletPositionEntity position : positions) {
                matched++;
                if (repairPrematureSettlement(position, notes)) {
                    repairedIds.add(position.getId());
                }
            }
        }
        return PrematureSettlementRepairSummary.builder()
                .scannedPositions(items.size())
                .matchedPositions(matched)
                .repairedPositions(repairedIds.size())
                .repairedPositionIds(repairedIds)
                .notes(notes.size() <= 20 ? notes : new ArrayList<>(notes.subList(0, 20)))
                .build();
    }

    public SettlementSyncSummary syncSettlements(int positionLimit, int marketLimit, boolean refreshCache) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"))
                .orderByAsc(SmartWalletPositionEntity::getLastFilledAt)
                .last("limit " + Math.max(positionLimit, 1)));
        return syncSettlementsForPositions(positions, marketLimit, refreshCache);
    }

    public SettlementSyncSummary syncSettlementsForMarkets(List<String> marketIds, boolean refreshCache) {
        if (marketIds == null || marketIds.isEmpty()) {
            return SettlementSyncSummary.builder()
                    .scannedPositions(0)
                    .distinctMarkets(0)
                    .fetchedMarkets(0)
                    .resolvedPositions(0)
                    .unresolvedMarkets(0)
                    .skippedFutureMarkets(0)
                    .failedMarkets(0)
                    .build();
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"))
                .in(SmartWalletPositionEntity::getMarketId, marketIds)
                .orderByAsc(SmartWalletPositionEntity::getLastFilledAt));
        return syncSettlementsForPositions(positions, marketIds.size(), refreshCache);
    }

    private SettlementSyncSummary syncSettlementsForPositions(List<SmartWalletPositionEntity> positions, int marketLimit, boolean refreshCache) {
        Set<String> markets = new LinkedHashSet<>();
        for (SmartWalletPositionEntity position : positions) {
            if (StringUtils.hasText(position.getMarketId()) && markets.size() < Math.max(marketLimit, 1)) {
                markets.add(position.getMarketId());
            }
        }
        int resolved = 0;
        int fetched = 0;
        int unresolved = 0;
        int skippedFuture = 0;
        int failed = 0;
        List<String> notes = new ArrayList<>();
        List<SettlementSyncFailure> failures = new ArrayList<>();
        Map<String, PolymarketApiClient.MarketMetadata> metadataByMarket = new LinkedHashMap<>();
        for (String marketId : markets) {
            try {
                PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(marketId, refreshCache);
                metadataByMarket.put(marketId, metadata);
                fetched++;
            } catch (Exception e) {
                failed++;
                String question = positions.stream()
                        .filter(position -> marketId.equalsIgnoreCase(position.getMarketId()))
                        .map(SmartWalletPositionEntity::getQuestion)
                        .filter(StringUtils::hasText)
                        .findFirst()
                        .orElse(null);
                String message = e.getMessage();
                notes.add("Metadata fetch failed for " + marketId + ": " + message);
                failures.add(SettlementSyncFailure.builder()
                        .marketId(marketId)
                        .question(question)
                        .errorMessage(message)
                        .build());
                log.warn("Failed to sync settlement for market {}: {}", marketId, message);
            }
        }
        for (SmartWalletPositionEntity position : positions) {
            PolymarketApiClient.MarketMetadata metadata = metadataByMarket.get(position.getMarketId());
            if (metadata == null) {
                continue;
            }
            if (metadata.getEndAt() != null && metadata.getEndAt() > System.currentTimeMillis() && !metadata.isClosed()) {
                skippedFuture++;
                continue;
            }
            if (!metadata.isResolved()) {
                if (metadata.isClosed()) {
                    notes.add("Market closed without explicit resolved outcome yet, skipping settlement for " + position.getMarketId());
                }
                unresolved++;
                continue;
            }
            resolvePosition(position, metadata);
            resolved++;
        }
        if (!notes.isEmpty()) {
            notes = notes.stream().limit(10).toList();
        }
        return SettlementSyncSummary.builder()
                .scannedPositions(positions.size())
                .distinctMarkets(markets.size())
                .fetchedMarkets(fetched)
                .resolvedPositions(resolved)
                .unresolvedMarkets(unresolved)
                .skippedFutureMarkets(skippedFuture)
                .failedMarkets(failed)
                .notes(notes)
                .failures(failures)
                .processedMarketIds(new ArrayList<>(markets))
                .build();
    }

    private PositionUpdateResult applyFillToPosition(SmartWalletFillEntity fill) {
        SmartWalletPositionEntity position = positionMapper.selectOne(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, fill.getExecutionAddress())
                .eq(SmartWalletPositionEntity::getMarketId, fill.getMarketId())
                .eq(SmartWalletPositionEntity::getOutcome, fill.getOutcome())
                .last("limit 1"));
        if (position == null) {
            position = new SmartWalletPositionEntity()
                    .setExecutionAddress(fill.getExecutionAddress())
                    .setMarketId(fill.getMarketId())
                    .setTokenId(fill.getTokenId())
                    .setQuestion(fill.getQuestion())
                    .setOutcome(fill.getOutcome())
                    .setNetSide(fill.getSide())
                    .setQuantity(BigDecimal.ZERO)
                    .setAverageEntryPrice(BigDecimal.ZERO)
                    .setCostBasisUsdc(BigDecimal.ZERO)
                    .setRealizedPnlUsdc(BigDecimal.ZERO)
                    .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                    .setLastMarkPrice(fill.getPrice())
                    .setOpenedAt(fill.getFilledAt())
                    .setStatus("OPEN")
                    .setCreatedAt(new Date())
                    .setUpdatedAt(new Date());
        }
        BigDecimal quantity = safe(position.getQuantity());
        BigDecimal costBasis = safe(position.getCostBasisUsdc());
        BigDecimal fillQty = safe(fill.getSize());
        BigDecimal fillNotional = safe(fill.getNotionalUsdc());
        BigDecimal realized = safe(position.getRealizedPnlUsdc());
        BigDecimal realizedDelta = BigDecimal.ZERO;
        BigDecimal previousQuantity = quantity;

        if ("SELL".equalsIgnoreCase(fill.getSide())) {
            BigDecimal averageCost = quantity.compareTo(BigDecimal.ZERO) > 0
                    ? costBasis.divide(quantity, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal reducedQty = fillQty.min(quantity);
            BigDecimal removedCost = averageCost.multiply(reducedQty);
            BigDecimal pnl = fillNotional.subtract(removedCost).subtract(safe(fill.getFeeUsdc()));
            quantity = quantity.subtract(reducedQty);
            costBasis = costBasis.subtract(removedCost).max(BigDecimal.ZERO);
            realized = realized.add(pnl);
            realizedDelta = pnl;
        } else {
            quantity = quantity.add(fillQty);
            costBasis = costBasis.add(fillNotional).add(safe(fill.getFeeUsdc()));
        }

        BigDecimal averageEntry = quantity.compareTo(BigDecimal.ZERO) > 0
                ? costBasis.divide(quantity, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        String lifecycleStatus;
        if (quantity.compareTo(BigDecimal.ZERO) == 0) {
            lifecycleStatus = "CLOSED";
        } else if ("SELL".equalsIgnoreCase(fill.getSide()) && previousQuantity.compareTo(BigDecimal.ZERO) > 0
                && quantity.compareTo(previousQuantity) < 0) {
            lifecycleStatus = "PARTIALLY_CLOSED";
        } else {
            lifecycleStatus = "OPEN";
        }

        position.setTokenId(fill.getTokenId())
                .setQuestion(fill.getQuestion())
                .setNetSide(quantity.compareTo(BigDecimal.ZERO) > 0 ? "BUY" : "FLAT")
                .setQuantity(quantity)
                .setAverageEntryPrice(averageEntry)
                .setCostBasisUsdc(costBasis)
                .setRealizedPnlUsdc(realized)
                .setLastMarkPrice(fill.getPrice())
                .setLastFilledAt(fill.getFilledAt())
                .setClosedAt(quantity.compareTo(BigDecimal.ZERO) == 0 ? fill.getFilledAt() : null)
                .setStatus(lifecycleStatus)
                .setUpdatedAt(new Date());
        markToMarket(position);
        if (position.getId() == null) {
            positionMapper.insert(position);
        } else {
            positionMapper.updateById(position);
        }
        return new PositionUpdateResult(position, realizedDelta);
    }

    private void appendCashLedger(SmartWalletOrderEntity order, SmartWalletFillEntity fill, PositionUpdateResult positionUpdate) {
        String direction = "SELL".equalsIgnoreCase(fill.getSide()) ? "IN" : "OUT";
        BigDecimal signedAmount = safe(fill.getNotionalUsdc());
        cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                .setExecutionAddress(fill.getExecutionAddress())
                .setAsset("USDC")
                .setEntryType("ORDER_NOTIONAL")
                .setDirection(direction)
                .setAmount(signedAmount)
                .setReferenceType("ORDER")
                .setReferenceId(order.getId())
                .setMarketId(fill.getMarketId())
                .setQuestion(fill.getQuestion())
                .setOccurredAt(fill.getFilledAt())
                .setCreatedAt(new Date()));
        if (safe(fill.getFeeUsdc()).compareTo(BigDecimal.ZERO) > 0) {
            cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                    .setExecutionAddress(fill.getExecutionAddress())
                    .setAsset("USDC")
                    .setEntryType("FEE")
                    .setDirection("OUT")
                    .setAmount(fill.getFeeUsdc())
                    .setReferenceType("FILL")
                    .setReferenceId(fill.getId())
                    .setMarketId(fill.getMarketId())
                    .setQuestion(fill.getQuestion())
                    .setOccurredAt(fill.getFilledAt())
                    .setCreatedAt(new Date()));
        }
        if (positionUpdate != null && positionUpdate.realizedDelta().compareTo(BigDecimal.ZERO) != 0) {
            cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                    .setExecutionAddress(fill.getExecutionAddress())
                    .setAsset("USDC")
                    .setEntryType("REALIZED_PNL")
                    .setDirection(positionUpdate.realizedDelta().compareTo(BigDecimal.ZERO) >= 0 ? "IN" : "OUT")
                    .setAmount(positionUpdate.realizedDelta().abs())
                    .setReferenceType("POSITION")
                    .setReferenceId(positionUpdate.position().getId())
                    .setMarketId(fill.getMarketId())
                    .setQuestion(fill.getQuestion())
                    .setOccurredAt(fill.getFilledAt())
                    .setCreatedAt(new Date()));
        }
    }

    private void rebuildDailyReport(String executionAddress, LocalDate reportDate) {
        Date start = Date.from(reportDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date end = Date.from(reportDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(StringUtils.hasText(executionAddress), SmartWalletOrderEntity::getExecutionAddress, executionAddress)
                .ge(SmartWalletOrderEntity::getPlacedAt, start)
                .lt(SmartWalletOrderEntity::getPlacedAt, end));
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(StringUtils.hasText(executionAddress), SmartWalletFillEntity::getExecutionAddress, executionAddress)
                .ge(SmartWalletFillEntity::getFilledAt, start)
                .lt(SmartWalletFillEntity::getFilledAt, end));
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(StringUtils.hasText(executionAddress), SmartWalletPositionEntity::getExecutionAddress, executionAddress));
        List<SmartWalletCashLedgerEntity> ledgerEntries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(StringUtils.hasText(executionAddress), SmartWalletCashLedgerEntity::getExecutionAddress, executionAddress)
                .ge(SmartWalletCashLedgerEntity::getOccurredAt, start)
                .lt(SmartWalletCashLedgerEntity::getOccurredAt, end));

        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        for (SmartWalletFillEntity fill : fills) {
            volume = volume.add(safe(fill.getNotionalUsdc()));
        }
        for (SmartWalletCashLedgerEntity entry : ledgerEntries) {
            if ("FEE".equalsIgnoreCase(entry.getEntryType())) {
                fee = fee.add(safe(entry.getAmount()));
            }
            if ("REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
                BigDecimal signed = "OUT".equalsIgnoreCase(entry.getDirection())
                        ? safe(entry.getAmount()).negate()
                        : safe(entry.getAmount());
                realized = realized.add(signed);
            }
        }
        BigDecimal unrealized = BigDecimal.ZERO;
        int wins = 0;
        for (SmartWalletPositionEntity position : positions) {
            markToMarket(position);
            unrealized = unrealized.add(safe(position.getUnrealizedPnlUsdc()));
            if (safe(position.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) > 0) {
                wins++;
            }
        }
        BigDecimal winRate = positions.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(positions.size()), 4, RoundingMode.HALF_UP);
        ConflictSummary conflictSummary = summarizeConflicts(positions, reportDate);

        SmartWalletDailyReportEntity entity = dailyReportMapper.selectOne(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .eq(SmartWalletDailyReportEntity::getReportDate, java.sql.Date.valueOf(reportDate))
                .eq(StringUtils.hasText(executionAddress), SmartWalletDailyReportEntity::getExecutionAddress, executionAddress)
                .last("limit 1"));
        if (entity == null) {
            entity = new SmartWalletDailyReportEntity()
                    .setReportDate(java.sql.Date.valueOf(reportDate))
                    .setExecutionAddress(executionAddress)
                    .setCreatedAt(new Date());
        }
        entity.setTotalOrders(orders.size())
                .setFilledOrders(fills.size())
                .setTotalVolumeUsdc(volume)
                .setFeeUsdc(fee)
                .setRealizedPnlUsdc(realized)
                .setUnrealizedPnlUsdc(unrealized)
                .setNetPnlUsdc(realized.add(unrealized).subtract(fee))
                .setWinRate(winRate)
                .setUpdatedAt(new Date());
        if (entity.getId() == null) {
            dailyReportMapper.insert(entity);
        } else {
            dailyReportMapper.updateById(entity);
        }
    }

    private void resolvePosition(SmartWalletPositionEntity position, PolymarketApiClient.MarketMetadata metadata) {
        if (position == null || safe(position.getQuantity()).compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Date now = new Date();
        BigDecimal resolutionPrice = settlementPrice(position, metadata);
        BigDecimal settlementProceeds = safe(position.getQuantity()).multiply(resolutionPrice).setScale(8, RoundingMode.HALF_UP);
        BigDecimal realizedDelta = settlementProceeds.subtract(safe(position.getCostBasisUsdc())).setScale(8, RoundingMode.HALF_UP);

        cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                .setExecutionAddress(position.getExecutionAddress())
                .setAsset("USDC")
                .setEntryType("SETTLEMENT_PROCEEDS")
                .setDirection(settlementProceeds.compareTo(BigDecimal.ZERO) >= 0 ? "IN" : "OUT")
                .setAmount(settlementProceeds.abs())
                .setReferenceType("POSITION")
                .setReferenceId(position.getId())
                .setMarketId(position.getMarketId())
                .setQuestion(position.getQuestion())
                .setOccurredAt(now)
                .setCreatedAt(new Date()));
        if (realizedDelta.compareTo(BigDecimal.ZERO) != 0) {
            cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                    .setExecutionAddress(position.getExecutionAddress())
                    .setAsset("USDC")
                    .setEntryType("REALIZED_PNL")
                    .setDirection(realizedDelta.compareTo(BigDecimal.ZERO) >= 0 ? "IN" : "OUT")
                    .setAmount(realizedDelta.abs())
                    .setReferenceType("POSITION")
                    .setReferenceId(position.getId())
                    .setMarketId(position.getMarketId())
                    .setQuestion(position.getQuestion())
                    .setOccurredAt(now)
                    .setCreatedAt(new Date()));
        }

        position.setRealizedPnlUsdc(safe(position.getRealizedPnlUsdc()).add(realizedDelta))
                .setUnrealizedPnlUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setLastMarkPrice(resolutionPrice)
                .setQuantity(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setCostBasisUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setAverageEntryPrice(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setNetSide("FLAT")
                .setStatus("RESOLVED")
                .setClosedAt(now)
                .setLastFilledAt(now)
                .setUpdatedAt(new Date());
        positionMapper.updateById(position);
        rebuildDailyReport(position.getExecutionAddress(), toLocalDate(now));
    }

    private BigDecimal settlementPrice(SmartWalletPositionEntity position, PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (!StringUtils.hasText(metadata.getResolvedOutcome())) {
            return metadata.isClosed() ? BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP) : safe(position.getLastMarkPrice());
        }
        return metadata.getResolvedOutcome().equalsIgnoreCase(position.getOutcome())
                ? BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }

    private void markToMarket(SmartWalletPositionEntity position) {
        SmartWalletTradeEventEntity latestEvent = tradeEventMapper.selectOne(new LambdaQueryWrapper<SmartWalletTradeEventEntity>()
                .eq(SmartWalletTradeEventEntity::getMarketId, position.getMarketId())
                .eq(SmartWalletTradeEventEntity::getOutcome, position.getOutcome())
                .orderByDesc(SmartWalletTradeEventEntity::getOpenedAt)
                .last("limit 1"));
        BigDecimal markPrice = latestEvent == null ? safe(position.getLastMarkPrice()) : safe(latestEvent.getPrice());
        BigDecimal unrealized = markPrice.multiply(safe(position.getQuantity()))
                .subtract(safe(position.getCostBasisUsdc()))
                .setScale(8, RoundingMode.HALF_UP);
        position.setLastMarkPrice(markPrice)
                .setUnrealizedPnlUsdc(unrealized)
                .setUpdatedAt(new Date());
        if (position.getId() != null) {
            positionMapper.updateById(position);
        }
    }

    private boolean isPrematureSettlement(SmartWalletPositionEntity position, boolean refreshCache, List<String> notes) {
        if (position == null || position.getClosedAt() == null || !StringUtils.hasText(position.getMarketId())) {
            return false;
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(position.getMarketId(), refreshCache);
            if (metadata == null || metadata.getEndAt() == null || metadata.getEndAt() <= 0) {
                return false;
            }
            if (position.getClosedAt().getTime() + 60_000L < metadata.getEndAt()) {
                notes.add("Detected premature resolved position id=" + position.getId() + " marketId=" + position.getMarketId());
                return true;
            }
            return false;
        } catch (Exception e) {
            notes.add("Failed to inspect market metadata for position " + position.getId() + ": " + e.getMessage());
            return false;
        }
    }

    private boolean repairPrematureSettlement(SmartWalletPositionEntity position, List<String> notes) {
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getExecutionAddress, position.getExecutionAddress())
                .eq(SmartWalletFillEntity::getMarketId, position.getMarketId())
                .eq(SmartWalletFillEntity::getOutcome, position.getOutcome())
                .orderByAsc(SmartWalletFillEntity::getFilledAt));
        if (fills.isEmpty()) {
            notes.add("Skipped repairing position " + position.getId() + " because no fills were found.");
            return false;
        }

        Date erroneousClosedAt = position.getClosedAt();
        cashLedgerMapper.delete(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(SmartWalletCashLedgerEntity::getReferenceType, "POSITION")
                .eq(SmartWalletCashLedgerEntity::getReferenceId, position.getId())
                .eq(SmartWalletCashLedgerEntity::getEntryType, "SETTLEMENT_PROCEEDS"));
        cashLedgerMapper.delete(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(SmartWalletCashLedgerEntity::getReferenceType, "POSITION")
                .eq(SmartWalletCashLedgerEntity::getReferenceId, position.getId())
                .eq(SmartWalletCashLedgerEntity::getEntryType, "REALIZED_PNL")
                .eq(erroneousClosedAt != null, SmartWalletCashLedgerEntity::getOccurredAt, erroneousClosedAt));

        PositionSnapshot snapshot = replayPositionFromFills(fills);
        position.setTokenId(snapshot.tokenId())
                .setQuestion(snapshot.question())
                .setNetSide(snapshot.quantity().compareTo(BigDecimal.ZERO) > 0 ? "BUY" : "FLAT")
                .setQuantity(snapshot.quantity())
                .setAverageEntryPrice(snapshot.averageEntryPrice())
                .setCostBasisUsdc(snapshot.costBasisUsdc())
                .setRealizedPnlUsdc(snapshot.realizedPnlUsdc())
                .setUnrealizedPnlUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setLastMarkPrice(snapshot.lastPrice())
                .setOpenedAt(snapshot.openedAt())
                .setLastFilledAt(snapshot.lastFilledAt())
                .setClosedAt(snapshot.quantity().compareTo(BigDecimal.ZERO) == 0 ? snapshot.lastFilledAt() : null)
                .setStatus(snapshot.status())
                .setUpdatedAt(new Date());
        positionMapper.updateById(position);

        if (snapshot.openedAt() != null) {
            rebuildDailyReport(position.getExecutionAddress(), toLocalDate(snapshot.openedAt()));
        }
        if (snapshot.lastFilledAt() != null) {
            rebuildDailyReport(position.getExecutionAddress(), toLocalDate(snapshot.lastFilledAt()));
        }
        if (erroneousClosedAt != null) {
            rebuildDailyReport(position.getExecutionAddress(), toLocalDate(erroneousClosedAt));
        }
        notes.add("Repaired premature settlement for position " + position.getId() + " => " + snapshot.status());
        return true;
    }

    private PositionSnapshot replayPositionFromFills(List<SmartWalletFillEntity> fills) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal lastPrice = BigDecimal.ZERO;
        String tokenId = null;
        String question = null;
        Date openedAt = null;
        Date lastFilledAt = null;
        boolean sellSeen = false;
        boolean partialSell = false;

        for (SmartWalletFillEntity fill : fills) {
            if (openedAt == null) {
                openedAt = fill.getFilledAt();
            }
            lastFilledAt = fill.getFilledAt();
            tokenId = fill.getTokenId();
            question = fill.getQuestion();
            lastPrice = safe(fill.getPrice());
            BigDecimal fillQty = safe(fill.getSize());
            BigDecimal fillNotional = safe(fill.getNotionalUsdc());
            if ("SELL".equalsIgnoreCase(fill.getSide())) {
                sellSeen = true;
                BigDecimal previousQuantity = quantity;
                BigDecimal averageCost = quantity.compareTo(BigDecimal.ZERO) > 0
                        ? costBasis.divide(quantity, 8, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal reducedQty = fillQty.min(quantity);
                BigDecimal removedCost = averageCost.multiply(reducedQty);
                BigDecimal pnl = fillNotional.subtract(removedCost).subtract(safe(fill.getFeeUsdc()));
                quantity = quantity.subtract(reducedQty);
                costBasis = costBasis.subtract(removedCost).max(BigDecimal.ZERO);
                realized = realized.add(pnl);
                if (previousQuantity.compareTo(BigDecimal.ZERO) > 0
                        && quantity.compareTo(previousQuantity) < 0
                        && quantity.compareTo(BigDecimal.ZERO) > 0) {
                    partialSell = true;
                }
            } else {
                quantity = quantity.add(fillQty);
                costBasis = costBasis.add(fillNotional).add(safe(fill.getFeeUsdc()));
            }
        }

        BigDecimal averageEntryPrice = quantity.compareTo(BigDecimal.ZERO) > 0
                ? costBasis.divide(quantity, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        String status;
        if (quantity.compareTo(BigDecimal.ZERO) == 0) {
            status = "CLOSED";
        } else if (sellSeen || partialSell) {
            status = "PARTIALLY_CLOSED";
        } else {
            status = "OPEN";
        }
        return new PositionSnapshot(
                quantity.setScale(8, RoundingMode.HALF_UP),
                averageEntryPrice.setScale(8, RoundingMode.HALF_UP),
                costBasis.setScale(8, RoundingMode.HALF_UP),
                realized.setScale(8, RoundingMode.HALF_UP),
                lastPrice,
                tokenId,
                question,
                openedAt,
                lastFilledAt,
                status
        );
    }

    private String latestExecutionAddress() {
        SmartWalletOrderEntity latest = orderMapper.selectOne(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .last("limit 1"));
        return latest == null ? null : latest.getExecutionAddress();
    }

    private LocalDate toLocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private BigDecimal calculateMaxDrawdown() {
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByAsc(SmartWalletDailyReportEntity::getReportDate));
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (SmartWalletDailyReportEntity report : reports) {
            cumulative = cumulative.add(safe(report.getNetPnlUsdc()));
            if (cumulative.compareTo(peak) > 0) {
                peak = cumulative;
            }
            BigDecimal drawdown = peak.subtract(cumulative);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown;
    }

    private BigDecimal deriveSize(BigDecimal notional, BigDecimal price) {
        if (safe(price).compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return safe(notional).divide(price, 8, RoundingMode.DOWN);
    }

    private BigDecimal safe(BigDecimal primary) {
        return primary == null ? BigDecimal.ZERO : primary;
    }

    private BigDecimal safe(BigDecimal primary, BigDecimal fallback) {
        return primary == null ? safe(fallback) : primary;
    }

    private SmartWalletOrderView toOrderView(SmartWalletOrderEntity order) {
        Long marketEndAt = resolveMarketEndAt(order.getMarketId());
        return SmartWalletOrderView.builder()
                .id(order.getId())
                .followTaskId(order.getFollowTaskId())
                .walletAddress(order.getWalletAddress())
                .executionMode(order.getExecutionMode())
                .executionAddress(order.getExecutionAddress())
                .externalOrderId(order.getExternalOrderId())
                .marketId(order.getMarketId())
                .tokenId(order.getTokenId())
                .question(order.getQuestion())
                .outcome(order.getOutcome())
                .marketEndAt(marketEndAt)
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(marketEndAt))
                .side(order.getSide())
                .orderType(order.getOrderType())
                .status(order.getStatus())
                .price(order.getPrice())
                .size(order.getSize())
                .notionalUsdc(order.getNotionalUsdc())
                .feeUsdc(order.getFeeUsdc())
                .errorMessage(order.getErrorMessage())
                .placedAt(order.getPlacedAt())
                .filledAt(order.getFilledAt())
                .build();
    }

    private SmartWalletFillView toFillView(SmartWalletFillEntity fill) {
        return SmartWalletFillView.builder()
                .id(fill.getId())
                .orderId(fill.getOrderId())
                .followTaskId(fill.getFollowTaskId())
                .walletAddress(fill.getWalletAddress())
                .executionAddress(fill.getExecutionAddress())
                .marketId(fill.getMarketId())
                .tokenId(fill.getTokenId())
                .question(fill.getQuestion())
                .outcome(fill.getOutcome())
                .side(fill.getSide())
                .price(fill.getPrice())
                .size(fill.getSize())
                .notionalUsdc(fill.getNotionalUsdc())
                .feeUsdc(fill.getFeeUsdc())
                .filledAt(fill.getFilledAt())
                .build();
    }

    private SmartWalletPositionView toPositionView(SmartWalletPositionEntity position) {
        Long marketEndAt = resolveMarketEndAt(position.getMarketId());
        return SmartWalletPositionView.builder()
                .id(position.getId())
                .executionAddress(position.getExecutionAddress())
                .marketId(position.getMarketId())
                .tokenId(position.getTokenId())
                .question(position.getQuestion())
                .outcome(position.getOutcome())
                .marketEndAt(marketEndAt)
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(marketEndAt))
                .netSide(position.getNetSide())
                .quantity(position.getQuantity())
                .averageEntryPrice(position.getAverageEntryPrice())
                .costBasisUsdc(position.getCostBasisUsdc())
                .realizedPnlUsdc(position.getRealizedPnlUsdc())
                .unrealizedPnlUsdc(position.getUnrealizedPnlUsdc())
                .lastMarkPrice(position.getLastMarkPrice())
                .status(position.getStatus())
                .openedAt(position.getOpenedAt())
                .lastFilledAt(position.getLastFilledAt())
                .closedAt(position.getClosedAt())
                .build();
    }

    private Long resolveMarketEndAt(String marketId) {
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(marketId);
            return metadata == null ? null : metadata.getEndAt();
        } catch (Exception e) {
            log.debug("Failed to resolve market end time for marketId={}: {}", marketId, e.getMessage());
            return null;
        }
    }

    private SmartWalletCashLedgerView toLedgerView(SmartWalletCashLedgerEntity item) {
        return SmartWalletCashLedgerView.builder()
                .id(item.getId())
                .executionAddress(item.getExecutionAddress())
                .asset(item.getAsset())
                .entryType(item.getEntryType())
                .direction(item.getDirection())
                .amount(item.getAmount())
                .balanceAfter(item.getBalanceAfter())
                .referenceType(item.getReferenceType())
                .referenceId(item.getReferenceId())
                .marketId(item.getMarketId())
                .question(item.getQuestion())
                .occurredAt(item.getOccurredAt())
                .build();
    }

    private SmartWalletDailyReportView toDailyReportView(SmartWalletDailyReportEntity report) {
        return SmartWalletDailyReportView.builder()
                .id(report.getId())
                .reportDate(report.getReportDate())
                .executionAddress(report.getExecutionAddress())
                .totalOrders(report.getTotalOrders())
                .filledOrders(report.getFilledOrders())
                .totalVolumeUsdc(report.getTotalVolumeUsdc())
                .feeUsdc(report.getFeeUsdc())
                .realizedPnlUsdc(report.getRealizedPnlUsdc())
                .unrealizedPnlUsdc(report.getUnrealizedPnlUsdc())
                .netPnlUsdc(report.getNetPnlUsdc())
                .winRate(report.getWinRate())
                .conflictingMarkets(calculateDailyConflictSummary(report).conflictingMarkets())
                .conflictingPositions(calculateDailyConflictSummary(report).conflictingPositions())
                .conflictNetPnlUsdc(calculateDailyConflictSummary(report).conflictNetPnlUsdc())
                .build();
    }

    private ConflictSummary calculateDailyConflictSummary(SmartWalletDailyReportEntity report) {
        if (report == null || report.getReportDate() == null) {
            return ConflictSummary.empty();
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(StringUtils.hasText(report.getExecutionAddress()), SmartWalletPositionEntity::getExecutionAddress, report.getExecutionAddress()));
        return summarizeConflicts(positions, toLocalDate(report.getReportDate()));
    }

    private ConflictSummary summarizeConflicts(List<SmartWalletPositionEntity> positions, LocalDate closedOnDate) {
        if (positions == null || positions.isEmpty()) {
            return ConflictSummary.empty();
        }
        Map<String, SmartWalletConflictMarketAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletPositionEntity position : positions) {
            if (!StringUtils.hasText(position.getMarketId())) {
                continue;
            }
            if (closedOnDate != null) {
                if (position.getClosedAt() == null || !closedOnDate.equals(toLocalDate(position.getClosedAt()))) {
                    continue;
                }
            }
            grouped.computeIfAbsent(position.getMarketId(),
                            key -> new SmartWalletConflictMarketAccumulator(position.getMarketId(), position.getQuestion()))
                    .add(position);
        }
        int conflictingMarkets = 0;
        int conflictingPositions = 0;
        int openConflictingMarkets = 0;
        int openConflictingPositions = 0;
        BigDecimal conflictNetPnlUsdc = BigDecimal.ZERO;
        for (SmartWalletConflictMarketAccumulator item : grouped.values()) {
            if (item.getOutcomes().size() < 2) {
                continue;
            }
            conflictingMarkets++;
            conflictingPositions += item.getPositionCount();
            conflictNetPnlUsdc = conflictNetPnlUsdc
                    .add(item.getRealizedPnlUsdc())
                    .add(item.getUnrealizedPnlUsdc());
            if (item.getOpenPositionCount() > 0) {
                openConflictingMarkets++;
                openConflictingPositions += item.getOpenPositionCount();
            }
        }
        return new ConflictSummary(
                conflictingMarkets,
                conflictingPositions,
                openConflictingMarkets,
                openConflictingPositions,
                conflictNetPnlUsdc
        );
    }

    private record PositionUpdateResult(SmartWalletPositionEntity position, BigDecimal realizedDelta) {
    }

    private record ConflictSummary(int conflictingMarkets,
                                   int conflictingPositions,
                                   int openConflictingMarkets,
                                   int openConflictingPositions,
                                   BigDecimal conflictNetPnlUsdc) {
        private static ConflictSummary empty() {
            return new ConflictSummary(0, 0, 0, 0, BigDecimal.ZERO);
        }
    }

    private record PositionSnapshot(BigDecimal quantity,
                                    BigDecimal averageEntryPrice,
                                    BigDecimal costBasisUsdc,
                                    BigDecimal realizedPnlUsdc,
                                    BigDecimal lastPrice,
                                    String tokenId,
                                    String question,
                                    Date openedAt,
                                    Date lastFilledAt,
                                    String status) {
    }

    private static class AttributionAccumulator {
        private final String key;
        private final String label;
        private int orderCount;
        private BigDecimal tradedVolumeUsdc = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal unrealizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal feeUsdc = BigDecimal.ZERO;

        private AttributionAccumulator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        private void addOrder(BigDecimal volume, BigDecimal fee) {
            this.orderCount++;
            this.tradedVolumeUsdc = this.tradedVolumeUsdc.add(volume);
            this.feeUsdc = this.feeUsdc.add(fee);
        }

        private void addPnl(BigDecimal realized, BigDecimal unrealized) {
            this.realizedPnlUsdc = this.realizedPnlUsdc.add(realized);
            this.unrealizedPnlUsdc = this.unrealizedPnlUsdc.add(unrealized);
        }

        private BigDecimal netPnl() {
            return realizedPnlUsdc.add(unrealizedPnlUsdc).subtract(feeUsdc);
        }

        private SmartWalletReportAttributionItem toView() {
            return SmartWalletReportAttributionItem.builder()
                    .key(key)
                    .label(label)
                    .orderCount(orderCount)
                    .tradedVolumeUsdc(tradedVolumeUsdc)
                    .realizedPnlUsdc(realizedPnlUsdc.add(unrealizedPnlUsdc))
                    .feeUsdc(feeUsdc)
                    .netPnlUsdc(netPnl())
                    .build();
        }
    }
}
