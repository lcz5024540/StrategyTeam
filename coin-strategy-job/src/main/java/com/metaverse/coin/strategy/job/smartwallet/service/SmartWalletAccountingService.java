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
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletClaimResultView;
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
import com.metaverse.coin.strategy.job.smartwallet.model.HistoricalPaperSettlementReviewView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncFailure;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Locale;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletAccountingService {

    private static final String PAPER_BOOK_EXECUTION_ADDRESS = "paper-book";

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
    private final ObjectProvider<SmartWalletFollowTaskService> followTaskServiceProvider;

    public void recordExecution(SmartWalletFollowTaskEntity task, SmartWalletExecutionResult result) {
        Date now = task.getExecutedAt() == null ? new Date() : task.getExecutedAt();
        BigDecimal price = safe(result.getPrice(), task.getTriggerPrice());
        BigDecimal notional = safe(result.getNotionalUsdc(), task.getRecommendedStakeUsdc());
        BigDecimal size = safe(result.getSize(), deriveSize(notional, price));
        BigDecimal fee = safe(result.getFeeUsdc(), BigDecimal.ZERO);
        String executionAddress = StringUtils.hasText(result.getExecutionAddress()) ? result.getExecutionAddress() : "paper-book";
        String externalOrderId = StringUtils.hasText(result.getExternalOrderId()) ? result.getExternalOrderId() : "local-" + task.getId();
        String status = StringUtils.hasText(result.getStatus()) ? result.getStatus() : (result.isSuccess() ? "FILLED" : "FAILED");
        Long marketEndAt = resolveTaskMarketEndAt(task);

        boolean isFilledImmediately = "FILLED".equalsIgnoreCase(status) || "MATCHED".equalsIgnoreCase(status) || (result.isSuccess() && "PAPER".equalsIgnoreCase(task.getExecutionMode()));

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
                .setMarketEndAt(marketEndAt)
                .setSide(StringUtils.hasText(result.getSide()) ? result.getSide() : task.getSide())
                .setOrderType(StringUtils.hasText(result.getOrderType()) ? result.getOrderType() : "GTC")
                .setStatus(status)
                .setPrice(price)
                .setSize(size)
                .setNotionalUsdc(notional)
                .setFeeUsdc(fee)
                .setErrorMessage(result.isSuccess() ? null : result.getMessage())
                .setPlacedAt(now)
                .setFilledAt(isFilledImmediately ? now : null)
                .setNextPollAt(("LIVE".equalsIgnoreCase(status) || "PARTIALLY_FILLED".equalsIgnoreCase(status)) ? new Date(now.getTime() + 1000) : null)
                .setPollAttempts(0)
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        orderMapper.insert(order);

        if (!isFilledImmediately) {
            if (!result.isSuccess()) {
                rebuildDailyReport(executionAddress, toLocalDate(now));
            }
            return; // 暂不生成成交记录
        }

        executeFillAndAccounting(order, size, price, fee, marketEndAt, now);
    }

    public void processOrderFill(SmartWalletOrderEntity order, BigDecimal filledSize, BigDecimal avgPrice, BigDecimal fee) {
        if (filledSize == null || filledSize.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        Long fillCount = fillMapper.selectCount(new LambdaQueryWrapper<SmartWalletFillEntity>().eq(SmartWalletFillEntity::getOrderId, order.getId()));
        if (fillCount != null && fillCount > 0) {
            return; // 已经处理过成交，防重
        }
        
        Date now = new Date();
        order.setFilledAt(now);
        orderMapper.updateById(order);

        executeFillAndAccounting(order, filledSize, avgPrice, fee, order.getMarketEndAt(), now);
    }

    private void executeFillAndAccounting(SmartWalletOrderEntity order, BigDecimal filledSize, BigDecimal avgPrice, BigDecimal fee, Long marketEndAt, Date now) {
        BigDecimal notional = filledSize.multiply(avgPrice);
        SmartWalletFillEntity fill = new SmartWalletFillEntity()
                .setOrderId(order.getId())
                .setFollowTaskId(order.getFollowTaskId())
                .setWalletAddress(order.getWalletAddress())
                .setExecutionAddress(order.getExecutionAddress())
                .setMarketId(order.getMarketId())
                .setTokenId(order.getTokenId())
                .setQuestion(order.getQuestion())
                .setOutcome(order.getOutcome())
                .setSide(order.getSide())
                .setPrice(avgPrice)
                .setSize(filledSize)
                .setNotionalUsdc(notional)
                .setFeeUsdc(fee)
                .setFilledAt(now)
                .setCreatedAt(new Date());
        fillMapper.insert(fill);

        PositionUpdateResult positionUpdate = applyFillToPosition(fill, marketEndAt);
        appendCashLedger(order, fill, positionUpdate);
        rebuildDailyReport(order.getExecutionAddress(), toLocalDate(now));
    }

    public List<SmartWalletOrderView> listOrders(int limit) {
        return listOrders(limit, null);
    }

    public List<SmartWalletOrderView> listOrders(int limit, String mode) {
        int safeLimit = Math.max(limit, 1);
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .orderByDesc(SmartWalletOrderEntity::getId));
        List<SmartWalletOrderView> result = new ArrayList<>();
        for (SmartWalletOrderEntity order : orders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode)) {
                continue;
            }
            result.add(toOrderView(order));
            if (result.size() >= safeLimit) {
                break;
            }
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
        return listActivePositions();
    }

    public List<SmartWalletPositionView> listActivePositions() {
        return listActivePositions(null);
    }

    public List<SmartWalletPositionView> listActivePositions(String mode) {
        List<SmartWalletPositionEntity> positions = loadPositionsByStatuses(List.of("OPEN", "PARTIALLY_CLOSED"));
        List<SmartWalletPositionView> result = new ArrayList<>();
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            result.add(toPositionView(position));
        }
        return result;
    }

    public List<SmartWalletPositionView> listPositions(boolean closedOnly) {
        return listPositions(closedOnly, null);
    }

    public List<SmartWalletPositionView> listPositions(boolean closedOnly, String mode) {
        List<SmartWalletPositionEntity> positions = closedOnly
                ? loadPositionsByStatuses(List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED"))
                : loadAllPositions();
        List<SmartWalletPositionView> result = new ArrayList<>();
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            result.add(toPositionView(position));
        }
        return result;
    }

    public List<SmartWalletPositionView> listNextSettlements(int limit) {
        return listNextSettlements(limit, null);
    }

    public List<SmartWalletPositionView> listNextSettlements(int limit, String mode) {
        long now = System.currentTimeMillis();
        List<SmartWalletPositionView> result = new ArrayList<>(loadPositionsByStatuses(List.of("OPEN", "PARTIALLY_CLOSED", "RESOLVED_UNCLAIMED")).stream()
                .filter(position -> matchesDashboardMode(position.getExecutionAddress(), mode))
                .map(this::toPositionView)
                .filter(position -> "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())
                        || (position.getMarketEndAt() != null && position.getMarketEndAt() > 0 && position.getMarketEndAt() <= now))
                .toList());
        result.sort((left, right) -> {
            boolean leftResolved = "RESOLVED_UNCLAIMED".equalsIgnoreCase(left.getStatus());
            boolean rightResolved = "RESOLVED_UNCLAIMED".equalsIgnoreCase(right.getStatus());
            if (leftResolved != rightResolved) {
                return leftResolved ? -1 : 1;
            }
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

    private List<SmartWalletPositionEntity> loadAllPositions() {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        for (SmartWalletPositionEntity position : positions) {
            markToMarket(position);
        }
        return positions;
    }

    private List<SmartWalletPositionEntity> loadPositionsByStatuses(List<String> statuses) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, statuses)
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        for (SmartWalletPositionEntity position : positions) {
            markToMarket(position);
        }
        return positions;
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

    public SmartWalletClaimResultView claimResolvedPosition(Long positionId, boolean confirmExternalClaim) {
        SmartWalletPositionEntity position = positionMapper.selectById(positionId);
        if (position == null) {
            throw new IllegalArgumentException("Position not found: " + positionId);
        }
        String previousStatus = position.getStatus();
        boolean externalClaimRequired = isLiveExecutionAddress(position.getExecutionAddress());
        if ("CLAIMED".equalsIgnoreCase(previousStatus)) {
            return SmartWalletClaimResultView.builder()
                    .positionId(position.getId())
                    .executionAddress(position.getExecutionAddress())
                    .previousStatus(previousStatus)
                    .currentStatus(previousStatus)
                    .externalClaimRequired(externalClaimRequired)
                    .externalClaimConfirmed(confirmExternalClaim)
                    .settlementProceedsUsdc(existingSettlementProceeds(position.getId()))
                    .message("Position is already claimed.")
                    .build();
        }
        if (!"RESOLVED_UNCLAIMED".equalsIgnoreCase(previousStatus)) {
            throw new IllegalStateException("Only RESOLVED_UNCLAIMED positions can be claimed. Current status=" + previousStatus);
        }
        if (externalClaimRequired && !confirmExternalClaim) {
            throw new IllegalStateException("LIVE position requires confirmExternalClaim=true after the on-chain claim is completed.");
        }

        BigDecimal settlementProceeds = existingSettlementProceeds(position.getId());
        Date now = new Date();
        if (settlementProceeds.compareTo(BigDecimal.ZERO) <= 0) {
            settlementProceeds = recomputeSettlementProceeds(position);
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
            rebuildDailyReport(position.getExecutionAddress(), toLocalDate(now));
        }

        position.setStatus("CLAIMED")
                .setUpdatedAt(new Date());
        positionMapper.updateById(position);

        return SmartWalletClaimResultView.builder()
                .positionId(position.getId())
                .executionAddress(position.getExecutionAddress())
                .previousStatus(previousStatus)
                .currentStatus(position.getStatus())
                .externalClaimRequired(externalClaimRequired)
                .externalClaimConfirmed(confirmExternalClaim)
                .settlementProceedsUsdc(settlementProceeds)
                .message(externalClaimRequired
                        ? "LIVE position marked as CLAIMED after external on-chain claim confirmation."
                        : "PAPER position claim completed.")
                .build();
    }

    public SmartWalletPnlSummaryView summary() {
        return summary(null);
    }

    public SmartWalletPnlSummaryView summary(String mode) {
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal fee = sumLedgerAmountByMode("FEE", mode, null, null);
        int filledOrders = 0;
        int wins = 0;
        int completedPositions = 0;
        int openPositions = 0;
        BigDecimal realized = sumLedgerSignedAmountByMode("REALIZED_PNL", mode, null, null);
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal pendingSettlement = BigDecimal.ZERO;
        for (SmartWalletFillEntity fill : fills) {
            if (!matchesDashboardMode(fill.getExecutionAddress(), mode)) {
                continue;
            }
            filledOrders++;
            volume = volume.add(safe(fill.getNotionalUsdc()));
        }
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            markToMarket(position);
            if (isActivePositionStatus(position.getStatus())) {
                if (isPendingSettlementWindow(position)) {
                    pendingSettlement = pendingSettlement.add(pendingSettlementNetPnlForPosition(position));
                } else {
                    unrealized = unrealized.add(safe(position.getUnrealizedPnlUsdc()));
                }
                openPositions++;
            }
            if (isClosedOrSettledStatus(position.getStatus())) {
                completedPositions++;
                if (realizedPnlForPosition(position).compareTo(BigDecimal.ZERO) > 0) {
                    wins++;
                }
            }
        }
        BigDecimal winRate = completedPositions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(completedPositions), 4, RoundingMode.HALF_UP);
        BigDecimal settledNet = realized.subtract(fee);
        BigDecimal composite = settledNet.add(unrealized);
        return SmartWalletPnlSummaryView.builder()
                .totalOrders(countOrdersByMode(mode))
                .filledOrders(filledOrders)
                .openPositions(openPositions)
                .tradedVolumeUsdc(volume)
                .feeUsdc(fee)
                .realizedPnlUsdc(realized)
                .unrealizedPnlUsdc(unrealized)
                .pendingSettlementPnlUsdc(pendingSettlement)
                .compositePnlUsdc(composite)
                .netPnlUsdc(settledNet)
                .winRate(winRate)
                .build();
    }

    public SmartWalletCapacitySummaryView capacitySummary() {
        return capacitySummary(null);
    }

    public SmartWalletCapacitySummaryView capacitySummary(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED")));
        int openPositionCount = positions.size();
        BigDecimal totalOpenCost = BigDecimal.ZERO;
        int filteredOpenPositionCount = 0;
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            filteredOpenPositionCount++;
            totalOpenCost = totalOpenCost.add(safe(position.getCostBasisUsdc()));
        }

        Integer openPositionLimit = properties.getSignalRisk().getMaxOpenPositions();
        BigDecimal openCostLimit = properties.getSignalRisk().getMaxTotalOpenCostUsdc();
        int resolvedPositionLimit = openPositionLimit == null || openPositionLimit <= 0 ? Integer.MAX_VALUE : openPositionLimit;
        BigDecimal availableCapital = availableCapitalUsdcByMode(mode);
        BigDecimal lockedClaimProceeds = lockedClaimProceedsUsdcByMode(mode);

        return SmartWalletCapacitySummaryView.builder()
                .openPositionCount(filteredOpenPositionCount)
                .openPositionLimit(openPositionLimit)
                .remainingPositionSlots(Math.max(0, resolvedPositionLimit == Integer.MAX_VALUE ? 0 : resolvedPositionLimit - filteredOpenPositionCount))
                .totalOpenCostUsdc(totalOpenCost)
                .openCostLimitUsdc(openCostLimit)
                .remainingCapitalBudgetUsdc(availableCapital.max(BigDecimal.ZERO))
                .availableCapitalUsdc(availableCapital)
                .lockedClaimProceedsUsdc(lockedClaimProceeds)
                .maxHoursToMarketEnd(properties.getSignalRisk().getMaxHoursToMarketEnd())
                .duplicateOpenPositionBlocked(properties.getSignalRisk().isBlockDuplicateOpenPosition())
                .duplicatePendingTaskBlocked(properties.getSignalRisk().isBlockDuplicatePendingTask())
                .build();
    }

    public BigDecimal availableCapitalUsdc() {
        return availableCapitalUsdc(null);
    }

    public BigDecimal availableCapitalUsdc(String executionAddress) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(StringUtils.hasText(executionAddress), SmartWalletPositionEntity::getExecutionAddress, executionAddress));
        BigDecimal realized = sumLedgerSignedAmount("REALIZED_PNL", executionAddress, null);
        BigDecimal openCost = BigDecimal.ZERO;
        BigDecimal lockedClaimProceeds = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            if ("OPEN".equalsIgnoreCase(position.getStatus()) || "PARTIALLY_CLOSED".equalsIgnoreCase(position.getStatus())) {
                openCost = openCost.add(safe(position.getCostBasisUsdc()));
            }
            lockedClaimProceeds = lockedClaimProceeds.add(lockedClaimProceedsForPosition(position));
        }
        BigDecimal budget = safe(properties.getSignalRisk().getMaxTotalOpenCostUsdc());
        return budget.add(realized).subtract(openCost).subtract(lockedClaimProceeds).setScale(8, RoundingMode.HALF_UP);
    }

    public BigDecimal lockedClaimProceedsUsdc() {
        return lockedClaimProceedsUsdc(null);
    }

    public BigDecimal lockedClaimProceedsUsdc(String executionAddress) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(StringUtils.hasText(executionAddress), SmartWalletPositionEntity::getExecutionAddress, executionAddress));
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            total = total.add(lockedClaimProceedsForPosition(position));
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    public List<SmartWalletConflictMarketView> conflictingMarkets(int limit, boolean openOnly) {
        return conflictingMarkets(limit, openOnly, null);
    }

    public List<SmartWalletConflictMarketView> conflictingMarkets(int limit, boolean openOnly, String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt));
        Map<String, SmartWalletConflictMarketAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            if (openOnly && !"OPEN".equalsIgnoreCase(position.getStatus()) && !"PARTIALLY_CLOSED".equalsIgnoreCase(position.getStatus())) {
                continue;
            }
            if (!StringUtils.hasText(position.getMarketId())) {
                continue;
            }
            grouped.computeIfAbsent(position.getMarketId(),
                            key -> new SmartWalletConflictMarketAccumulator(position.getMarketId(), position.getQuestion()))
                    .add(position, realizedPnlForPosition(position));
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
        return performance(null);
    }

    public SmartWalletPerformanceView performance(String mode) {
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> filteredPositions = positions.stream()
                .filter(position -> matchesDashboardMode(position.getExecutionAddress(), mode))
                .toList();
        ConflictSummary conflictSummary = summarizeConflicts(filteredPositions, null);
        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal fee = sumLedgerAmountByMode("FEE", mode, null, null);
        int filledOrders = 0;
        int openPositions = 0;
        int closedPositions = 0;
        int winningPositions = 0;
        int losingPositions = 0;
        BigDecimal realized = sumLedgerSignedAmountByMode("REALIZED_PNL", mode, null, null);
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal pendingSettlement = BigDecimal.ZERO;
        BigDecimal totalWins = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;
        for (SmartWalletFillEntity fill : fills) {
            if (!matchesDashboardMode(fill.getExecutionAddress(), mode)) {
                continue;
            }
            filledOrders++;
            volume = volume.add(safe(fill.getNotionalUsdc()));
        }
        for (SmartWalletPositionEntity position : filteredPositions) {
            markToMarket(position);
            BigDecimal positionRealized = realizedPnlForPosition(position);
            BigDecimal positionUnrealized = isActivePositionStatus(position.getStatus()) && !isPendingSettlementWindow(position)
                    ? safe(position.getUnrealizedPnlUsdc())
                    : BigDecimal.ZERO;
            unrealized = unrealized.add(positionUnrealized);
            if (isPendingSettlementWindow(position)) {
                pendingSettlement = pendingSettlement.add(pendingSettlementNetPnlForPosition(position));
            }
            if (isClosedOrSettledStatus(position.getStatus())) {
                closedPositions++;
                if (positionRealized.compareTo(BigDecimal.ZERO) > 0) {
                    winningPositions++;
                    totalWins = totalWins.add(positionRealized);
                }
                if (positionRealized.compareTo(BigDecimal.ZERO) < 0) {
                    losingPositions++;
                    totalLosses = totalLosses.add(positionRealized.abs());
                }
            } else {
                openPositions++;
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
        BigDecimal maxDrawdown = calculateMaxDrawdown(mode);
        BigDecimal settledNet = realized.subtract(fee);
        BigDecimal composite = settledNet.add(unrealized);
        return SmartWalletPerformanceView.builder()
                .totalOrders(countOrdersByMode(mode))
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
                .pendingSettlementPnlUsdc(pendingSettlement)
                .compositePnlUsdc(composite)
                .netPnlUsdc(settledNet)
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
        return pnlTimeline(days, null);
    }

    public List<SmartWalletPnlTimelinePoint> pnlTimeline(int days, String mode) {
        int safeDays = Math.max(days, 1);
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByAsc(SmartWalletDailyReportEntity::getReportDate));
        List<SmartWalletDailyReportEntity> filteredReports = reports.stream()
                .filter(report -> matchesDashboardMode(report.getExecutionAddress(), mode))
                .toList();
        int fromIndex = Math.max(filteredReports.size() - safeDays, 0);
        List<SmartWalletPnlTimelinePoint> points = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (SmartWalletDailyReportEntity report : filteredReports.subList(fromIndex, filteredReports.size())) {
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
        return listDailyReports(limit, null);
    }

    public List<SmartWalletDailyReportView> listDailyReports(int limit, String mode) {
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByDesc(SmartWalletDailyReportEntity::getReportDate));
        List<SmartWalletDailyReportView> result = new ArrayList<>();
        int safeLimit = Math.max(limit, 1);
        for (SmartWalletDailyReportEntity report : reports) {
            if (!matchesDashboardMode(report.getExecutionAddress(), mode)) {
                continue;
            }
            result.add(toDailyReportView(report));
            if (result.size() >= safeLimit) {
                break;
            }
        }
        return result;
    }

    public List<SmartWalletReportAttributionItem> walletAttribution(int limit) {
        return walletAttribution(limit, null);
    }

    public List<SmartWalletReportAttributionItem> walletAttribution(int limit, String mode) {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getStatus, "FILLED"));
        List<SmartWalletCashLedgerEntity> ledgerEntries = cashLedgerMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, AttributionAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : orders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode)) {
                continue;
            }
            String key = order.getExecutionAddress();
            AttributionAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new AttributionAccumulator(key, key));
            acc.addOrder(safe(order.getNotionalUsdc()));
        }
        for (SmartWalletCashLedgerEntity entry : ledgerEntries) {
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            String key = entry.getExecutionAddress();
            AttributionAccumulator acc = grouped.computeIfAbsent(key, ignored -> new AttributionAccumulator(key, key));
            acc.addLedger(entry);
        }
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            String key = position.getExecutionAddress();
            AttributionAccumulator acc = grouped.computeIfAbsent(key, ignored -> new AttributionAccumulator(key, key));
            acc.addExposure(position, lockedClaimProceedsForPosition(position));
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(AttributionAccumulator::netPnl).reversed())
                .limit(Math.max(limit, 1))
                .map(AttributionAccumulator::toView)
                .toList();
    }

    public List<SmartWalletReportAttributionItem> marketAttribution(int limit) {
        return marketAttribution(limit, null);
    }

    public List<SmartWalletReportAttributionItem> marketAttribution(int limit, String mode) {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getStatus, "FILLED"));
        List<SmartWalletCashLedgerEntity> ledgerEntries = cashLedgerMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, AttributionAccumulator> grouped = new LinkedHashMap<>();
        for (SmartWalletOrderEntity order : orders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode)) {
                continue;
            }
            String key = order.getMarketId();
            AttributionAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new AttributionAccumulator(key, order.getQuestion()));
            acc.addOrder(safe(order.getNotionalUsdc()));
        }
        for (SmartWalletCashLedgerEntity entry : ledgerEntries) {
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            String key = entry.getMarketId();
            AttributionAccumulator acc = grouped.computeIfAbsent(key,
                    ignored -> new AttributionAccumulator(key, entry.getQuestion() == null ? key : entry.getQuestion()));
            acc.addLedger(entry);
        }
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            AttributionAccumulator acc = grouped.computeIfAbsent(position.getMarketId(),
                    ignored -> new AttributionAccumulator(position.getMarketId(), position.getQuestion()));
            acc.addExposure(position, lockedClaimProceedsForPosition(position));
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

    public Map<String, Object> backfillMissingMarketEndAt(int limit, boolean refreshCache) {
        int safeLimit = Math.max(limit, 1);
        int updatedPositions = backfillPositionMarketEndAt(safeLimit, refreshCache);
        int updatedOrders = backfillOrderMarketEndAt(safeLimit, refreshCache);
        int updatedTasks = backfillTaskMarketEndAt(safeLimit, refreshCache);
        return Map.of(
                "scannedLimit", safeLimit,
                "updatedPositions", updatedPositions,
                "updatedOrders", updatedOrders,
                "updatedTasks", updatedTasks,
                "totalUpdated", updatedPositions + updatedOrders + updatedTasks
        );
    }

    public PrematureSettlementRepairSummary repairPrematureResolvedPaperPositions(int limit, boolean refreshCache) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")
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
                        && "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus()))
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
                    .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")
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

    public HistoricalPaperSettlementReviewView reviewHistoricalPaperSettlements(int limit, boolean refreshCache) {
        List<SmartWalletPositionEntity> openPaperPositions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"))
                .orderByAsc(SmartWalletPositionEntity::getLastFilledAt)
                .last("limit " + Math.max(limit, 1)));
        SettlementSyncSummary syncSummary = syncSettlementsForPositions(openPaperPositions, Math.max(limit, 1), refreshCache);

        List<SmartWalletPositionEntity> unclaimedPaperPositions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")
                .orderByAsc(SmartWalletPositionEntity::getClosedAt)
                .last("limit " + Math.max(limit, 1)));
        List<Long> autoClaimedPositionIds = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (SmartWalletPositionEntity position : unclaimedPaperPositions) {
            try {
                SmartWalletClaimResultView result = claimResolvedPosition(position.getId(), false);
                if ("CLAIMED".equalsIgnoreCase(result.getCurrentStatus())) {
                    autoClaimedPositionIds.add(position.getId());
                }
            } catch (RuntimeException e) {
                notes.add("Auto-claim failed for paper position " + position.getId() + ": " + e.getMessage());
            }
        }
        if (!unclaimedPaperPositions.isEmpty() && autoClaimedPositionIds.isEmpty()) {
            notes.add("Resolved paper positions were found, but no simulated claim completed in this review run.");
        }
        if (syncSummary != null && syncSummary.getNotes() != null) {
            notes.addAll(syncSummary.getNotes());
        }
        if (notes.size() > 20) {
            notes = new ArrayList<>(notes.subList(0, 20));
        }
        return HistoricalPaperSettlementReviewView.builder()
                .scannedPositions(openPaperPositions.size())
                .autoClaimedPositions(autoClaimedPositionIds.size())
                .settlementSync(syncSummary)
                .autoClaimedPositionIds(autoClaimedPositionIds)
                .notes(notes)
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
        Set<String> localPaperMarkets = registerPaperMarketMetadataHints(positions);
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
            String question = positions.stream()
                    .filter(position -> marketId.equalsIgnoreCase(position.getMarketId()))
                    .map(SmartWalletPositionEntity::getQuestion)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            List<String> outcomeHints = positions.stream()
                    .filter(position -> marketId.equalsIgnoreCase(position.getMarketId()))
                    .map(SmartWalletPositionEntity::getOutcome)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            try {
                PolymarketApiClient.MarketMetadata metadata = localPaperMarkets.contains(marketId)
                        ? marketMetadataService.getBestEffortByConditionId(marketId, refreshCache, question, outcomeHints)
                        : marketMetadataService.getByConditionId(marketId, refreshCache, question);
                metadataByMarket.put(marketId, metadata);
                fetched++;
            } catch (Exception e) {
                failed++;
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
                // For paper-book positions with expired markets, try to infer the outcome from prices
                boolean isPaper = isPaperExecutionAddress(position.getExecutionAddress());
                boolean marketExpired = (metadata.getEndAt() != null && metadata.getEndAt() <= System.currentTimeMillis())
                        || (position.getMarketEndAt() != null && position.getMarketEndAt() <= System.currentTimeMillis());
                if (isPaper && marketExpired) {
                    String inferredOutcome = inferResolvedOutcomeFromPrices(metadata.getOutcomes(), metadata.getOutcomePrices());
                    if (StringUtils.hasText(inferredOutcome)) {
                        PolymarketApiClient.MarketMetadata resolvedMetadata = PolymarketApiClient.MarketMetadata.builder()
                                .conditionId(metadata.getConditionId())
                                .marketId(metadata.getMarketId())
                                .question(metadata.getQuestion())
                                .category(metadata.getCategory())
                                .minimumTickSize(metadata.getMinimumTickSize())
                                .negRisk(metadata.isNegRisk())
                                .active(metadata.isActive())
                                .closed(true)
                                .acceptingOrders(false)
                                .resolvedOutcome(inferredOutcome)
                                .endAt(metadata.getEndAt())
                                .outcomes(metadata.getOutcomes())
                                .outcomePrices(metadata.getOutcomePrices())
                                .outcomeTokenIds(metadata.getOutcomeTokenIds())
                                .build();
                        notes.add("Paper position " + position.getId() + " auto-resolved with inferred outcome: " + inferredOutcome);
                        log.info("Auto-resolving expired paper position: positionId={}, marketId={}, inferredOutcome={}",
                                position.getId(), position.getMarketId(), inferredOutcome);
                        resolvePosition(position, resolvedMetadata);
                        resolved++;
                        continue;
                    }
                }
                if (metadata.isClosed()) {
                    notes.add("Market closed without explicit resolved outcome yet, skipping settlement for " + position.getMarketId());
                    log.warn("Settlement unresolved after market close: marketId={}, question={}, outcomes={}, outcomePrices={}, acceptingOrders={}",
                            position.getMarketId(),
                            position.getQuestion(),
                            metadata.getOutcomes(),
                            metadata.getOutcomePrices(),
                            metadata.isAcceptingOrders());
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

    private Set<String> registerPaperMarketMetadataHints(List<SmartWalletPositionEntity> positions) {
        if (positions == null || positions.isEmpty()) {
            return Set.of();
        }
        Set<String> liveMarkets = positions.stream()
                .filter(position -> position != null && isLiveExecutionAddress(position.getExecutionAddress()))
                .map(SmartWalletPositionEntity::getMarketId)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, List<SmartWalletPositionEntity>> grouped = new LinkedHashMap<>();
        for (SmartWalletPositionEntity position : positions) {
            if (position == null
                    || !isPaperExecutionAddress(position.getExecutionAddress())
                    || !StringUtils.hasText(position.getMarketId())
                    || liveMarkets.contains(position.getMarketId())) {
                continue;
            }
            grouped.computeIfAbsent(position.getMarketId(), ignored -> new ArrayList<>()).add(position);
        }
        Set<String> registered = new LinkedHashSet<>();
        grouped.forEach((marketId, marketPositions) -> {
            String question = marketPositions.stream()
                    .map(SmartWalletPositionEntity::getQuestion)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            List<String> outcomes = marketPositions.stream()
                    .map(SmartWalletPositionEntity::getOutcome)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            marketMetadataService.registerLocalMetadata(marketId, question, null, outcomes);
            registered.add(marketId);
        });
        return registered;
    }

    private PositionUpdateResult applyFillToPosition(SmartWalletFillEntity fill, Long marketEndAt) {
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
                    .setMarketEndAt(marketEndAt)
                    .setNetSide(fill.getSide())
                    .setQuantity(BigDecimal.ZERO)
                    .setAverageEntryPrice(BigDecimal.ZERO)
                    .setCostBasisUsdc(BigDecimal.ZERO)
                    .setOriginalCostBasisUsdc(BigDecimal.ZERO)
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
                .setMarketEndAt(resolveStoredMarketEndAt(position.getMarketEndAt(), marketEndAt, fill.getMarketId()))
                .setNetSide(quantity.compareTo(BigDecimal.ZERO) > 0 ? "BUY" : "FLAT")
                .setQuantity(quantity)
                .setAverageEntryPrice(averageEntry)
                .setCostBasisUsdc(costBasis)
                .setOriginalCostBasisUsdc(costBasis)
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
        SmartWalletDailyReportEntity entity = dailyReportMapper.selectOne(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .eq(SmartWalletDailyReportEntity::getReportDate, java.sql.Date.valueOf(reportDate))
                .eq(StringUtils.hasText(executionAddress), SmartWalletDailyReportEntity::getExecutionAddress, executionAddress)
                .last("limit 1"));

        BigDecimal unrealized = BigDecimal.ZERO;
        int closedPositions = 0;
        int wins = 0;
        boolean currentDay = reportDate.equals(LocalDate.now(ZoneId.systemDefault()));
        for (SmartWalletPositionEntity position : positions) {
            if (currentDay && isActivePositionStatus(position.getStatus())) {
                markToMarket(position);
                unrealized = unrealized.add(safe(position.getUnrealizedPnlUsdc()));
            }
            if (position.getClosedAt() != null && reportDate.equals(toLocalDate(position.getClosedAt()))) {
                closedPositions++;
                if (safe(position.getRealizedPnlUsdc()).compareTo(BigDecimal.ZERO) > 0) {
                    wins++;
                }
            }
        }
        if (!currentDay) {
            unrealized = BigDecimal.ZERO;
        }
        BigDecimal winRate = closedPositions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(closedPositions), 4, RoundingMode.HALF_UP);

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
                .setOriginalCostBasisUsdc(safe(position.getCostBasisUsdc()).setScale(8, RoundingMode.HALF_UP))
                .setCostBasisUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setAverageEntryPrice(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setNetSide("FLAT")
                .setStatus("RESOLVED_UNCLAIMED")
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

    /**
     * Infer the resolved outcome from outcome prices for paper-book positions.
     * Uses a relaxed threshold (>= 0.95) to detect a clear winner when the market has expired.
     * Returns null if no clear winner can be determined.
     */
    private String inferResolvedOutcomeFromPrices(List<String> outcomes, List<String> outcomePrices) {
        if (outcomes == null || outcomePrices == null || outcomes.isEmpty() || outcomePrices.isEmpty()) {
            return null;
        }
        if (outcomes.size() != outcomePrices.size()) {
            return null;
        }
        String winner = null;
        BigDecimal highestPrice = BigDecimal.ZERO;
        for (int i = 0; i < outcomes.size(); i++) {
            String outcome = outcomes.get(i);
            BigDecimal price;
            try {
                price = new BigDecimal(outcomePrices.get(i));
            } catch (NumberFormatException e) {
                continue;
            }
            // Use relaxed threshold (>= 0.95) for paper-book expired markets
            if (price.compareTo(new BigDecimal("0.95")) >= 0) {
                if (winner != null) {
                    // Multiple winners, cannot determine
                    return null;
                }
                winner = outcome;
                highestPrice = price;
            } else if (price.compareTo(new BigDecimal("0.05")) > 0 && winner == null) {
                // If no clear winner yet, track the highest price as potential winner
                if (price.compareTo(highestPrice) > 0) {
                    highestPrice = price;
                    winner = outcome;
                }
            }
        }
        // Only return winner if it has a dominant price (>= 0.95)
        // or if there's a single outcome with price > 0.80 and all others are < 0.20
        if (winner != null && highestPrice.compareTo(new BigDecimal("0.95")) >= 0) {
            return winner;
        }
        // Fallback: check if one outcome clearly dominates (> 0.80 and others < 0.20)
        if (outcomes.size() == 2) {
            try {
                BigDecimal price0 = new BigDecimal(outcomePrices.get(0));
                BigDecimal price1 = new BigDecimal(outcomePrices.get(1));
                if (price0.compareTo(new BigDecimal("0.80")) > 0 && price1.compareTo(new BigDecimal("0.20")) < 0) {
                    return outcomes.get(0);
                }
                if (price1.compareTo(new BigDecimal("0.80")) > 0 && price0.compareTo(new BigDecimal("0.20")) < 0) {
                    return outcomes.get(1);
                }
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return null;
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
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(position.getMarketId(), refreshCache, position.getQuestion());
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
                .setOriginalCostBasisUsdc(snapshot.costBasisUsdc())
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
        return calculateMaxDrawdown(null);
    }

    private BigDecimal calculateMaxDrawdown(String mode) {
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByAsc(SmartWalletDailyReportEntity::getReportDate));
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (SmartWalletDailyReportEntity report : reports) {
            if (!matchesDashboardMode(report.getExecutionAddress(), mode)) {
                continue;
            }
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

    private boolean isSettledStatus(String status) {
        return "RESOLVED_UNCLAIMED".equalsIgnoreCase(status) || "CLAIMED".equalsIgnoreCase(status);
    }

    private boolean isActivePositionStatus(String status) {
        return "OPEN".equalsIgnoreCase(status) || "PARTIALLY_CLOSED".equalsIgnoreCase(status);
    }

    private boolean isPendingSettlementWindow(SmartWalletPositionEntity position) {
        if (position == null || !isActivePositionStatus(position.getStatus())) {
            return false;
        }
        Long marketEndAt = position.getMarketEndAt();
        if (marketEndAt == null || marketEndAt <= 0) {
            marketEndAt = resolveStoredPositionMarketEndAt(position);
        }
        return marketEndAt != null && marketEndAt > 0 && marketEndAt <= System.currentTimeMillis();
    }

    private boolean isClosedOrSettledStatus(String status) {
        return "CLOSED".equalsIgnoreCase(status) || isSettledStatus(status);
    }

    private BigDecimal lockedClaimProceedsForPosition(SmartWalletPositionEntity position) {
        if (position == null || !"RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())) {
            return BigDecimal.ZERO;
        }
        return recomputeSettlementProceeds(position);
    }

    private boolean isLiveExecutionAddress(String executionAddress) {
        return StringUtils.hasText(executionAddress) && !PAPER_BOOK_EXECUTION_ADDRESS.equalsIgnoreCase(executionAddress);
    }

    private boolean isPaperExecutionAddress(String executionAddress) {
        return !StringUtils.hasText(executionAddress) || PAPER_BOOK_EXECUTION_ADDRESS.equalsIgnoreCase(executionAddress);
    }

    private BigDecimal existingSettlementProceeds(Long positionId) {
        if (positionId == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return sumLedgerSignedAmount("SETTLEMENT_PROCEEDS", null, "POSITION", positionId);
    }

    private BigDecimal recomputeSettlementProceeds(SmartWalletPositionEntity position) {
        List<SmartWalletFillEntity> fills = loadPositionFills(position);
        if (fills.isEmpty()) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        PositionSnapshot snapshot = replayPositionFromFills(fills);
        PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(position.getMarketId(), false, position.getQuestion());
        BigDecimal price = settlementPrice(position, metadata);
        return safe(snapshot.quantity()).multiply(price).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal originalCostBasisForPosition(SmartWalletPositionEntity position) {
        if (position == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (!isSettledStatus(position.getStatus())) {
            return safe(position.getCostBasisUsdc()).setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal stored = position.getOriginalCostBasisUsdc();
        if (stored != null && stored.compareTo(BigDecimal.ZERO) > 0) {
            return stored.setScale(8, RoundingMode.HALF_UP);
        }
        List<SmartWalletFillEntity> fills = loadPositionFills(position);
        if (fills.isEmpty()) {
            return safe(position.getCostBasisUsdc()).setScale(8, RoundingMode.HALF_UP);
        }
        return replayPositionFromFills(fills).costBasisUsdc();
    }

    private BigDecimal pendingSettlementNetPnlForPosition(SmartWalletPositionEntity position) {
        if (position == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (isActivePositionStatus(position.getStatus())) {
            return safe(position.getUnrealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        }
        if ("RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())) {
            return lockedClaimProceedsForPosition(position)
                    .subtract(originalCostBasisForPosition(position))
                    .setScale(8, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }

    private List<SmartWalletFillEntity> loadPositionFills(SmartWalletPositionEntity position) {
        if (position == null) {
            return List.of();
        }
        return fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getExecutionAddress, position.getExecutionAddress())
                .eq(SmartWalletFillEntity::getMarketId, position.getMarketId())
                .eq(SmartWalletFillEntity::getOutcome, position.getOutcome())
                .orderByAsc(SmartWalletFillEntity::getFilledAt));
    }

    private BigDecimal realizedPnlForPosition(SmartWalletPositionEntity position) {
        if (position == null || position.getId() == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return sumLedgerSignedAmount("REALIZED_PNL", position.getExecutionAddress(), "POSITION", position.getId());
    }

    private BigDecimal safe(BigDecimal primary) {
        return primary == null ? BigDecimal.ZERO : primary;
    }

    private BigDecimal safe(BigDecimal primary, BigDecimal fallback) {
        return primary == null ? safe(fallback) : primary;
    }

    private int countOrdersByMode(String mode) {
        int total = 0;
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        for (SmartWalletOrderEntity order : orders) {
            if (matchesDashboardMode(order.getExecutionAddress(), mode)) {
                total++;
            }
        }
        return total;
    }

    private BigDecimal availableCapitalUsdcByMode(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        BigDecimal realized = sumLedgerSignedAmountByMode("REALIZED_PNL", mode, null, null);
        BigDecimal openCost = BigDecimal.ZERO;
        BigDecimal lockedClaimProceeds = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            if ("OPEN".equalsIgnoreCase(position.getStatus()) || "PARTIALLY_CLOSED".equalsIgnoreCase(position.getStatus())) {
                openCost = openCost.add(safe(position.getCostBasisUsdc()));
            }
            lockedClaimProceeds = lockedClaimProceeds.add(lockedClaimProceedsForPosition(position));
        }
        BigDecimal budget = safe(properties.getSignalRisk().getMaxTotalOpenCostUsdc());
        return budget.add(realized).subtract(openCost).subtract(lockedClaimProceeds).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal lockedClaimProceedsUsdcByMode(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            total = total.add(lockedClaimProceedsForPosition(position));
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private String normalizeDashboardMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return null;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if ("PAPER".equals(normalized) || "LIVE".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private boolean matchesDashboardMode(String executionAddress, String mode) {
        String normalized = normalizeDashboardMode(mode);
        if (normalized == null) {
            return true;
        }
        return "PAPER".equals(normalized)
                ? isPaperExecutionAddress(executionAddress)
                : isLiveExecutionAddress(executionAddress);
    }

    private BigDecimal sumLedgerAmount(String entryType, String executionAddress, String referenceType, Long referenceId) {
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(StringUtils.hasText(entryType), SmartWalletCashLedgerEntity::getEntryType, entryType)
                .eq(StringUtils.hasText(executionAddress), SmartWalletCashLedgerEntity::getExecutionAddress, executionAddress)
                .eq(StringUtils.hasText(referenceType), SmartWalletCashLedgerEntity::getReferenceType, referenceType)
                .eq(referenceId != null, SmartWalletCashLedgerEntity::getReferenceId, referenceId));
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletCashLedgerEntity entry : entries) {
            total = total.add(safe(entry.getAmount()));
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal sumLedgerAmount(String entryType, String executionAddress, Long referenceId) {
        return sumLedgerAmount(entryType, executionAddress, null, referenceId);
    }

    private BigDecimal sumLedgerAmountByMode(String entryType, String mode, String referenceType, Long referenceId) {
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(StringUtils.hasText(entryType), SmartWalletCashLedgerEntity::getEntryType, entryType)
                .eq(StringUtils.hasText(referenceType), SmartWalletCashLedgerEntity::getReferenceType, referenceType)
                .eq(referenceId != null, SmartWalletCashLedgerEntity::getReferenceId, referenceId));
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            total = total.add(safe(entry.getAmount()));
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal sumLedgerSignedAmount(String entryType, String executionAddress, String referenceType, Long referenceId) {
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(StringUtils.hasText(entryType), SmartWalletCashLedgerEntity::getEntryType, entryType)
                .eq(StringUtils.hasText(executionAddress), SmartWalletCashLedgerEntity::getExecutionAddress, executionAddress)
                .eq(StringUtils.hasText(referenceType), SmartWalletCashLedgerEntity::getReferenceType, referenceType)
                .eq(referenceId != null, SmartWalletCashLedgerEntity::getReferenceId, referenceId));
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletCashLedgerEntity entry : entries) {
            BigDecimal signed = "OUT".equalsIgnoreCase(entry.getDirection())
                    ? safe(entry.getAmount()).negate()
                    : safe(entry.getAmount());
            total = total.add(signed);
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal sumLedgerSignedAmount(String entryType, String executionAddress, Long referenceId) {
        return sumLedgerSignedAmount(entryType, executionAddress, null, referenceId);
    }

    private BigDecimal sumLedgerSignedAmountByMode(String entryType, String mode, String referenceType, Long referenceId) {
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(StringUtils.hasText(entryType), SmartWalletCashLedgerEntity::getEntryType, entryType)
                .eq(StringUtils.hasText(referenceType), SmartWalletCashLedgerEntity::getReferenceType, referenceType)
                .eq(referenceId != null, SmartWalletCashLedgerEntity::getReferenceId, referenceId));
        BigDecimal total = BigDecimal.ZERO;
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            BigDecimal signed = "OUT".equalsIgnoreCase(entry.getDirection())
                    ? safe(entry.getAmount()).negate()
                    : safe(entry.getAmount());
            total = total.add(signed);
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private SmartWalletOrderView toOrderView(SmartWalletOrderEntity order) {
        Long marketEndAt = resolveStoredOrderMarketEndAt(order);
        SignalRiskDecision decision = resolveOrderRiskDecision(order);
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
                .expectedNetEvUsdc(decision == null ? null : decision.getExpectedNetEvUsdc())
                .estimatedFeeUsdc(decision == null ? null : decision.getEstimatedFeeUsdc())
                .estimatedSlippageUsdc(decision == null ? null : decision.getEstimatedSlippageUsdc())
                .followProbability(decision == null ? null : decision.getFollowProbability())
                .calibratedFollowProbability(decision == null ? null : decision.getCalibratedFollowProbability())
                .slippageSpikeProbability(decision == null ? null : decision.getSlippageSpikeProbability())
                .evSampleSize(decision == null ? null : decision.getEvSampleSize())
                .errorMessage(order.getErrorMessage())
                .placedAt(order.getPlacedAt())
                .filledAt(order.getFilledAt())
                .build();
    }

    private SignalRiskDecision resolveOrderRiskDecision(SmartWalletOrderEntity order) {
        if (order == null || order.getFollowTaskId() == null) {
            return null;
        }
        try {
            SmartWalletFollowTaskService followTaskService = followTaskServiceProvider.getIfAvailable();
            if (followTaskService == null) {
                return null;
            }
            return followTaskService.evaluateTask(order.getFollowTaskId());
        } catch (Exception ex) {
            log.debug("Failed to resolve order EV for orderId={} followTaskId={}: {}",
                    order.getId(), order.getFollowTaskId(), ex.getMessage());
            return null;
        }
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
        Long marketEndAt = resolveStoredPositionMarketEndAt(position);
        BigDecimal originalCostBasis = originalCostBasisForPosition(position);
        BigDecimal claimableProceeds = "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())
                ? lockedClaimProceedsForPosition(position)
                : BigDecimal.ZERO;
        BigDecimal pendingSettlementNetPnl = "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())
                ? claimableProceeds.subtract(originalCostBasis).setScale(8, RoundingMode.HALF_UP)
                : safe(position.getUnrealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
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
                .originalCostBasisUsdc(originalCostBasis)
                .realizedPnlUsdc(position.getRealizedPnlUsdc())
                .unrealizedPnlUsdc(position.getUnrealizedPnlUsdc())
                .pendingSettlementNetPnlUsdc(pendingSettlementNetPnl)
                .claimableProceedsUsdc(claimableProceeds)
                .lastMarkPrice(position.getLastMarkPrice())
                .status(position.getStatus())
                .openedAt(position.getOpenedAt())
                .lastFilledAt(position.getLastFilledAt())
                .closedAt(position.getClosedAt())
                .build();
    }

    private Long resolveMarketEndAt(String marketId) {
        return resolveMarketEndAt(marketId, null);
    }

    private Long resolveMarketEndAt(String marketId, String questionHint) {
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(marketId, false, questionHint);
            return metadata == null ? null : metadata.getEndAt();
        } catch (Exception e) {
            log.debug("Failed to resolve market end time for marketId={}: {}", marketId, e.getMessage());
            return null;
        }
    }

    private Long resolveTaskMarketEndAt(SmartWalletFollowTaskEntity task) {
        if (task == null) {
            return null;
        }
        if (task.getMarketEndAt() != null && task.getMarketEndAt() > 0) {
            return task.getMarketEndAt();
        }
        Long marketEndAt = resolveMarketEndAt(task.getMarketId(), task.getQuestion());
        if (marketEndAt != null && marketEndAt > 0 && task.getId() != null) {
            task.setMarketEndAt(marketEndAt);
            task.setUpdatedAt(new Date());
            followTaskMapper.updateById(task);
        }
        return marketEndAt;
    }

    private Long resolveStoredOrderMarketEndAt(SmartWalletOrderEntity order) {
        if (order == null) {
            return null;
        }
        if (order.getMarketEndAt() != null && order.getMarketEndAt() > 0) {
            return order.getMarketEndAt();
        }
        Long marketEndAt = resolveMarketEndAt(order.getMarketId(), order.getQuestion());
        if (marketEndAt != null && marketEndAt > 0 && order.getId() != null) {
            order.setMarketEndAt(marketEndAt);
            order.setUpdatedAt(new Date());
            orderMapper.updateById(order);
        }
        return marketEndAt;
    }

    private Long resolveStoredPositionMarketEndAt(SmartWalletPositionEntity position) {
        if (position == null) {
            return null;
        }
        if (position.getMarketEndAt() != null && position.getMarketEndAt() > 0) {
            return position.getMarketEndAt();
        }
        Long marketEndAt = resolveMarketEndAt(position.getMarketId(), position.getQuestion());
        if (marketEndAt != null && marketEndAt > 0 && position.getId() != null) {
            position.setMarketEndAt(marketEndAt);
            position.setUpdatedAt(new Date());
            positionMapper.updateById(position);
        }
        return marketEndAt;
    }

    private Long resolveStoredMarketEndAt(Long primary, Long fallback, String marketId) {
        if (primary != null && primary > 0) {
            return primary;
        }
        if (fallback != null && fallback > 0) {
            return fallback;
        }
        return resolveMarketEndAt(marketId, null);
    }

    private int backfillPositionMarketEndAt(int limit, boolean refreshCache) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .and(wrapper -> wrapper.isNull(SmartWalletPositionEntity::getMarketEndAt)
                        .or()
                        .le(SmartWalletPositionEntity::getMarketEndAt, 0L))
                .orderByAsc(SmartWalletPositionEntity::getId)
                .last("limit " + limit));
        int updated = 0;
        for (SmartWalletPositionEntity position : positions) {
            try {
                PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(position.getMarketId(), refreshCache, position.getQuestion());
                if (metadata != null && metadata.getEndAt() != null && metadata.getEndAt() > 0) {
                    position.setMarketEndAt(metadata.getEndAt());
                    position.setUpdatedAt(new Date());
                    positionMapper.updateById(position);
                    updated++;
                }
            } catch (Exception e) {
                log.debug("Failed to backfill position marketEndAt for positionId={}: {}", position.getId(), e.getMessage());
            }
        }
        return updated;
    }

    private int backfillOrderMarketEndAt(int limit, boolean refreshCache) {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .and(wrapper -> wrapper.isNull(SmartWalletOrderEntity::getMarketEndAt)
                        .or()
                        .le(SmartWalletOrderEntity::getMarketEndAt, 0L))
                .orderByAsc(SmartWalletOrderEntity::getId)
                .last("limit " + limit));
        int updated = 0;
        for (SmartWalletOrderEntity order : orders) {
            try {
                PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(order.getMarketId(), refreshCache, order.getQuestion());
                if (metadata != null && metadata.getEndAt() != null && metadata.getEndAt() > 0) {
                    order.setMarketEndAt(metadata.getEndAt());
                    order.setUpdatedAt(new Date());
                    orderMapper.updateById(order);
                    updated++;
                }
            } catch (Exception e) {
                log.debug("Failed to backfill order marketEndAt for orderId={}: {}", order.getId(), e.getMessage());
            }
        }
        return updated;
    }

    private int backfillTaskMarketEndAt(int limit, boolean refreshCache) {
        List<SmartWalletFollowTaskEntity> tasks = followTaskMapper.selectList(new LambdaQueryWrapper<SmartWalletFollowTaskEntity>()
                .and(wrapper -> wrapper.isNull(SmartWalletFollowTaskEntity::getMarketEndAt)
                        .or()
                        .le(SmartWalletFollowTaskEntity::getMarketEndAt, 0L))
                .orderByAsc(SmartWalletFollowTaskEntity::getId)
                .last("limit " + limit));
        int updated = 0;
        for (SmartWalletFollowTaskEntity task : tasks) {
            try {
                PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getByConditionId(task.getMarketId(), refreshCache, task.getQuestion());
                if (metadata != null && metadata.getEndAt() != null && metadata.getEndAt() > 0) {
                    task.setMarketEndAt(metadata.getEndAt());
                    task.setUpdatedAt(new Date());
                    followTaskMapper.updateById(task);
                    updated++;
                }
            } catch (Exception e) {
                log.debug("Failed to backfill follow-task marketEndAt for taskId={}: {}", task.getId(), e.getMessage());
            }
        }
        return updated;
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
                    .add(position, realizedPnlForPosition(position));
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

    private class AttributionAccumulator {
        private final String key;
        private final String label;
        private int orderCount;
        private BigDecimal tradedVolumeUsdc = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal pendingSettlementPnlUsdc = BigDecimal.ZERO;
        private BigDecimal feeUsdc = BigDecimal.ZERO;

        private AttributionAccumulator(String key, String label) {
            this.key = key;
            this.label = label;
        }

        private void addOrder(BigDecimal volume) {
            this.orderCount++;
            this.tradedVolumeUsdc = this.tradedVolumeUsdc.add(volume);
        }

        private void addLedger(SmartWalletCashLedgerEntity entry) {
            if (entry == null || !StringUtils.hasText(entry.getEntryType())) {
                return;
            }
            if ("FEE".equalsIgnoreCase(entry.getEntryType())) {
                this.feeUsdc = this.feeUsdc.add(safe(entry.getAmount()));
                return;
            }
            if ("REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
                BigDecimal signed = "OUT".equalsIgnoreCase(entry.getDirection())
                        ? safe(entry.getAmount()).negate()
                        : safe(entry.getAmount());
                this.realizedPnlUsdc = this.realizedPnlUsdc.add(signed);
            }
        }

        private void addExposure(SmartWalletPositionEntity position, BigDecimal claimableProceeds) {
            if (position == null) {
                return;
            }
            if (isActivePositionStatus(position.getStatus())) {
                this.pendingSettlementPnlUsdc = this.pendingSettlementPnlUsdc.add(safe(position.getUnrealizedPnlUsdc()));
            } else if ("RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())) {
                BigDecimal netPendingSettlement = safe(claimableProceeds).subtract(originalCostBasisForPosition(position));
                this.pendingSettlementPnlUsdc = this.pendingSettlementPnlUsdc.add(netPendingSettlement);
            }
        }

        private BigDecimal netPnl() {
            return realizedPnlUsdc.add(pendingSettlementPnlUsdc).subtract(feeUsdc);
        }

        private SmartWalletReportAttributionItem toView() {
            return SmartWalletReportAttributionItem.builder()
                    .key(key)
                    .label(label)
                    .orderCount(orderCount)
                    .tradedVolumeUsdc(tradedVolumeUsdc)
                    .realizedPnlUsdc(realizedPnlUsdc)
                    .pendingSettlementPnlUsdc(pendingSettlementPnlUsdc)
                    .feeUsdc(feeUsdc)
                    .netPnlUsdc(netPnl())
                    .build();
        }
    }
}
