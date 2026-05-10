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
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
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
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowAddressDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketLinkView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.strategy.model.CursorPageView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReportAttributionItem;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletTradeView;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.PrematureSettlementRepairItem;
import com.metaverse.coin.strategy.job.smartwallet.model.HistoricalPaperSettlementReviewView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementRetryCandidate;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class SmartWalletAccountingService {

    private static final String PAPER_BOOK_EXECUTION_ADDRESS = "paper-book";
    private static final String POSITION_STATUS_SETTLEMENT_PENDING_ORACLE = "SETTLEMENT_PENDING_ORACLE";
    private static final String POSITION_STATUS_WAIT_FINAL_PRICE = "WAIT_FINAL_PRICE";
    private static final String POSITION_STATUS_NEED_MANUAL_CHECK = "NEED_MANUAL_CHECK";
    private static final String ORDER_STATUS_SETTLEMENT_PENDING_ORACLE = "SETTLEMENT_PENDING_ORACLE";
    private static final String ORDER_STATUS_WAIT_FINAL_PRICE = "WAIT_FINAL_PRICE";
    private static final String ORDER_STATUS_NEED_MANUAL_CHECK = "NEED_MANUAL_CHECK";
    private static final String ORDER_STATUS_SETTLED_WIN = "SETTLED_WIN";
    private static final String ORDER_STATUS_SETTLED_LOSS = "SETTLED_LOSS";
    private static final String ORDER_TYPE_EXIT = "EXIT";
    private static final String TAKE_PROFIT_EXIT_REASON = "TAKE_PROFIT_AUTO_EXIT";
    private static final String STOP_LOSS_EXIT_REASON = "STOP_LOSS_AUTO_EXIT";
    private static final String ASSET_USDC_E = "USDC.e";
    private static final List<String> EXECUTED_ORDER_STATUSES = List.of(
            "FILLED",
            "MATCHED",
            ORDER_STATUS_SETTLEMENT_PENDING_ORACLE,
            ORDER_STATUS_WAIT_FINAL_PRICE,
            ORDER_STATUS_NEED_MANUAL_CHECK,
            ORDER_STATUS_SETTLED_WIN,
            ORDER_STATUS_SETTLED_LOSS
    );
    private static final List<String> SETTLEMENT_CANDIDATE_POSITION_STATUSES = List.of(
            "OPEN",
            "PARTIALLY_CLOSED",
            POSITION_STATUS_SETTLEMENT_PENDING_ORACLE,
            POSITION_STATUS_WAIT_FINAL_PRICE,
            POSITION_STATUS_NEED_MANUAL_CHECK
    );

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
    private final SmartWalletMarketInfoService marketInfoService;
    private final SmartWalletMarketStateService marketStateService;
    private final SmartWalletMarketLinkService marketLinkService;
    private final ObjectProvider<SmartWalletTradeOrderService> tradeOrderServiceProvider;

    // ---- PnL Summary / Performance 缓存（避免每次全表扫描）----
    private static final long PNL_CACHE_TTL_MS = 60_000L;
    private volatile SmartWalletPnlSummaryView cachedPnlSummary;
    private volatile String cachedPnlSummaryMode;
    private volatile long cachedPnlSummaryExpiresAt;

    private volatile SmartWalletPerformanceView cachedPerformance;
    private volatile String cachedPerformanceMode;
    private volatile long cachedPerformanceExpiresAt;

    public void recordExecution(SmartWalletFollowTaskEntity task, SmartWalletExecutionResult result) {
        marketInfoService.populate(task);
        if (task == null || !StringUtils.hasText(task.getMarketId()) || !StringUtils.hasText(task.getOutcome())) {
            throw new IllegalStateException("Follow task market info is missing before execution accounting. taskId="
                    + (task == null ? null : task.getId())
                    + ", marketInfoId=" + (task == null ? null : task.getMarketInfoId()));
        }
        Date now = task.getExecutedAt() == null ? new Date() : task.getExecutedAt();
        BigDecimal price = safe(result.getPrice(), task.getTriggerPrice());
        BigDecimal notional = safe(result.getNotionalUsdc(), task.getRecommendedStakeUsdc());
        BigDecimal size = safe(result.getSize(), deriveSize(notional, price));
        BigDecimal tradingFeeUsdc = resolveTradingFeeUsdc(result);
        BigDecimal gasFeeUsdc = resolveGasFeeUsdc(result);
        BigDecimal fee = totalFeeUsdc(tradingFeeUsdc, gasFeeUsdc);
        String executionAddress = StringUtils.hasText(result.getExecutionAddress()) ? result.getExecutionAddress() : "paper-book";
        String externalOrderId = StringUtils.hasText(result.getExternalOrderId()) ? result.getExternalOrderId() : "local-" + task.getId();
        String status = StringUtils.hasText(result.getStatus()) ? result.getStatus() : (result.isSuccess() ? "FILLED" : "FAILED");
        Long marketEndAt = resolveTaskMarketEndAt(task);

        boolean isFilledImmediately = "FILLED".equalsIgnoreCase(status) || "MATCHED".equalsIgnoreCase(status) || (result.isSuccess() && "PAPER".equalsIgnoreCase(task.getExecutionMode()));

        SmartWalletMarketInfoEntity mi = marketInfoService.getOrCreate(task.getMarketId(), task.getOutcome(), task.getQuestion(), result.getTokenId(), result.getIndexSet(), marketEndAt);
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setFollowTaskId(task.getId())
                .setWalletAddress(task.getWalletAddress())
                .setExecutionMode(task.getExecutionMode())
                .setExecutionAddress(executionAddress)
                .setExternalOrderId(externalOrderId)
                .setMarketInfoId(mi.getId())
                .setMarketInfo(mi)
                .setSide(StringUtils.hasText(result.getSide()) ? result.getSide() : task.getSide())
                .setOrderType(StringUtils.hasText(result.getOrderType()) ? result.getOrderType() : "GTC")
                .setStatus(status)
                .setPrice(price)
                .setSize(size)
                .setNotionalUsdc(notional)
                .setFeeUsdc(fee)
                .setTradingFeeUsdc(tradingFeeUsdc)
                .setFeePol(safe(result.getFeePol()))
                .setGasFeeUsdc(gasFeeUsdc)
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

        executeFillAndAccounting(order, size, price, tradingFeeUsdc, gasFeeUsdc, marketEndAt, now);
    }

    public void processOrderFill(SmartWalletOrderEntity order, BigDecimal filledSize, BigDecimal avgPrice, BigDecimal fee) {
        if (order == null || filledSize == null || filledSize.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal alreadyFilled = existingFilledSize(order.getId());
        BigDecimal deltaFilled = filledSize.subtract(alreadyFilled).setScale(8, RoundingMode.HALF_UP);
        if (deltaFilled.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        Date now = new Date();
        order.setFilledAt(now);
        updateOrderChecked(order, "processOrderFill");

        marketInfoService.populate(order);
        executeFillAndAccounting(order,
                deltaFilled,
                avgPrice,
                safe(order.getTradingFeeUsdc(), fee).setScale(8, RoundingMode.HALF_UP),
                safe(order.getGasFeeUsdc()).setScale(8, RoundingMode.HALF_UP),
                order.getMarketEndAt(),
                now);
    }

    public void recordLiveExitExecution(SmartWalletPositionEntity position,
                                        SmartWalletExecutionResult result,
                                        String exitReason) {
        if (position == null) {
            throw new IllegalArgumentException("position must be provided for live exit execution recording.");
        }
        if (result == null) {
            throw new IllegalArgumentException("result must be provided for live exit execution recording.");
        }

        marketInfoService.populate(position);
        Date now = new Date();
        SmartWalletFillEntity latestFill = latestPositionFill(position);
        BigDecimal price = safe(result.getPrice(), safe(position.getLastMarkPrice(), position.getAverageEntryPrice()));
        BigDecimal size = safe(result.getSize(), position.getQuantity());
        BigDecimal notional = safe(result.getNotionalUsdc(), size.multiply(price).setScale(8, RoundingMode.HALF_UP));
        BigDecimal tradingFeeUsdc = resolveTradingFeeUsdc(result);
        BigDecimal gasFeeUsdc = resolveGasFeeUsdc(result);
        BigDecimal fee = totalFeeUsdc(tradingFeeUsdc, gasFeeUsdc);
        String executionAddress = StringUtils.hasText(result.getExecutionAddress())
                ? result.getExecutionAddress()
                : position.getExecutionAddress();
        String externalOrderId = StringUtils.hasText(result.getExternalOrderId())
                ? result.getExternalOrderId()
                : "local-live-exit-" + position.getId() + "-" + now.getTime();
        String status = StringUtils.hasText(result.getStatus())
                ? result.getStatus()
                : (result.isSuccess() ? "LIVE" : "FAILED");
        boolean isFilledImmediately = "FILLED".equalsIgnoreCase(status) || "MATCHED".equalsIgnoreCase(status);

        SmartWalletMarketInfoEntity mi = marketInfoService.getOrCreate(
                position.getMarketId(), position.getOutcome(), position.getQuestion(),
                StringUtils.hasText(result.getTokenId()) ? result.getTokenId() : position.getTokenId(),
                result.getIndexSet() != null ? result.getIndexSet() : position.getIndexSet(),
                resolveStoredPositionMarketEndAt(position));
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setFollowTaskId(latestFill == null ? null : latestFill.getFollowTaskId())
                .setPositionId(position.getId())
                .setWalletAddress(latestFill == null ? null : latestFill.getWalletAddress())
                .setExecutionMode("LIVE")
                .setExecutionAddress(executionAddress)
                .setExternalOrderId(externalOrderId)
                .setMarketInfoId(mi.getId())
                .setMarketInfo(mi)
                .setSide(StringUtils.hasText(result.getSide()) ? result.getSide() : "SELL")
                .setOrderType(StringUtils.hasText(result.getOrderType()) ? result.getOrderType() : "EXIT")
                .setStatus(status)
                .setPrice(price)
                .setSize(size)
                .setNotionalUsdc(notional)
                .setFeeUsdc(fee)
                .setTradingFeeUsdc(tradingFeeUsdc)
                .setFeePol(safe(result.getFeePol()))
                .setGasFeeUsdc(gasFeeUsdc)
                .setExitReason(normalizeExitReason(exitReason))
                .setErrorMessage(result.isSuccess() ? null : buildLiveExitFailureMessage(exitReason, result.getMessage()))
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
            return;
        }

        executeFillAndAccounting(order, size, price, tradingFeeUsdc, gasFeeUsdc, position.getMarketEndAt(), now);
    }

    public SmartWalletPositionExitResultView exitPosition(Long positionId, SmartWalletPositionExitRequest request) {
        SmartWalletPositionEntity position = positionMapper.selectById(positionId);
        if (position == null) {
            throw new IllegalArgumentException("Position not found: " + positionId);
        }
        if (!isPaperExecutionAddress(position.getExecutionAddress())) {
            throw new IllegalStateException("Manual exit is currently supported only for PAPER positions. executionAddress="
                    + position.getExecutionAddress());
        }

        String previousStatus = position.getStatus();
        if (!isActivePositionStatus(previousStatus) && !isOraclePendingPositionStatus(previousStatus)) {
            throw new IllegalStateException("Only OPEN, PARTIALLY_CLOSED or SETTLEMENT_PENDING_ORACLE positions can exit early. Current status=" + previousStatus);
        }

        markToMarket(position);
        BigDecimal availableQuantity = safe(position.getQuantity()).setScale(8, RoundingMode.HALF_UP);
        if (availableQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Position has no remaining quantity to exit.");
        }

        BigDecimal requestedQuantity = request == null ? null : request.getQuantity();
        BigDecimal exitQuantity = requestedQuantity == null || requestedQuantity.compareTo(BigDecimal.ZERO) <= 0
                ? availableQuantity
                : requestedQuantity.setScale(8, RoundingMode.HALF_UP);
        if (exitQuantity.compareTo(availableQuantity) > 0) {
            throw new IllegalArgumentException("Exit quantity " + exitQuantity + " exceeds remaining quantity " + availableQuantity);
        }

        BigDecimal requestedPrice = request == null ? null : request.getPrice();
        BigDecimal exitPrice = safe(requestedPrice, position.getLastMarkPrice()).setScale(8, RoundingMode.HALF_UP);
        if (exitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Exit price must be provided, or the position must have a usable lastMarkPrice.");
        }

        BigDecimal feeUsdc = safe(request == null ? null : request.getFeeUsdc()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal tradingFeeUsdc = safe(request == null ? null : request.getTradingFeeUsdc(),
                request == null ? null : request.getFeeUsdc()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal feePol = safe(request == null ? null : request.getFeePol()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal gasFeeUsdc = safe(request == null ? null : request.getGasFeeUsdc(),
                feeUsdc.subtract(tradingFeeUsdc)).max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP);
        feeUsdc = totalFeeUsdc(tradingFeeUsdc, gasFeeUsdc);
        Date now = new Date();
        marketInfoService.populate(position);
        SmartWalletFillEntity latestFill = latestPositionFill(position);
        Long resolvedMarketEndAt = resolveStoredPositionMarketEndAt(position);
        // Update market_info with marketEndAt if resolved
        if (resolvedMarketEndAt != null && resolvedMarketEndAt > 0 && position.getMarketInfo() != null
                && (position.getMarketInfo().getMarketEndAt() == null || position.getMarketInfo().getMarketEndAt() <= 0)) {
            position.getMarketInfo().setMarketEndAt(resolvedMarketEndAt);
        }
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setFollowTaskId(latestFill == null ? null : latestFill.getFollowTaskId())
                .setPositionId(position.getId())
                .setWalletAddress(latestFill == null ? null : latestFill.getWalletAddress())
                .setExecutionMode(isPaperExecutionAddress(position.getExecutionAddress()) ? "PAPER" : "LIVE")
                .setExecutionAddress(position.getExecutionAddress())
                .setExternalOrderId("paper-exit-" + position.getId() + "-" + now.getTime())
                .setMarketInfoId(position.getMarketInfoId())
                .setMarketInfo(position.getMarketInfo())
                .setSide("SELL")
                .setOrderType("EXIT")
                .setStatus("FILLED")
                .setPrice(exitPrice)
                .setSize(exitQuantity)
                .setNotionalUsdc(exitQuantity.multiply(exitPrice).setScale(8, RoundingMode.HALF_UP))
                .setFeeUsdc(feeUsdc)
                .setTradingFeeUsdc(tradingFeeUsdc)
                .setFeePol(feePol)
                .setGasFeeUsdc(gasFeeUsdc)
                .setExitReason(normalizeExitReason(request == null ? null : request.getReason()))
                .setErrorMessage(buildExitOrderMessage(request == null ? null : request.getReason()))
                .setPlacedAt(now)
                .setFilledAt(now)
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
        orderMapper.insert(order);

        BigDecimal realizedBefore = safe(position.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        executeFillAndAccounting(order, exitQuantity, exitPrice, tradingFeeUsdc, gasFeeUsdc, resolvedMarketEndAt, now);

        SmartWalletPositionEntity refreshed = positionMapper.selectById(positionId);
        if (refreshed == null) {
            refreshed = position;
        }
        marketInfoService.populate(refreshed);
        BigDecimal realizedAfter = safe(refreshed.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal realizedDelta = realizedAfter.subtract(realizedBefore).setScale(8, RoundingMode.HALF_UP);
        syncEntryOrdersAfterPaperExit(refreshed, order, realizedDelta, now);
        BigDecimal proceeds = exitQuantity.multiply(exitPrice).setScale(8, RoundingMode.HALF_UP);
        BigDecimal remainingQuantity = safe(refreshed.getQuantity()).setScale(8, RoundingMode.HALF_UP);
        boolean fullyClosed = remainingQuantity.compareTo(BigDecimal.ZERO) == 0;
        return SmartWalletPositionExitResultView.builder()
                .positionId(refreshed.getId())
                .orderId(order.getId())
                .executionAddress(refreshed.getExecutionAddress())
                .marketId(refreshed.getMarketId())
                .outcome(refreshed.getOutcome())
                .previousStatus(previousStatus)
                .currentStatus(refreshed.getStatus())
                .exitPrice(exitPrice)
                .exitQuantity(exitQuantity)
                .exitProceedsUsdc(proceeds)
                .realizedPnlDeltaUsdc(realizedDelta)
                .totalRealizedPnlUsdc(realizedAfter)
                .remainingQuantity(remainingQuantity)
                .message(fullyClosed
                        ? "Position exited before oracle settlement. Realized PnL is now locked in and no further settlement will apply."
                        : "Position partially exited. Remaining quantity will continue through the normal market expiry and oracle settlement flow.")
                .build();
    }

    private BigDecimal existingFilledSize(Long orderId) {
        if (orderId == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal total = BigDecimal.ZERO;
        final int pageSize = 1000;
        long lastId = 0L;
        while (true) {
            List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                    .eq(SmartWalletFillEntity::getOrderId, orderId)
                    .gt(SmartWalletFillEntity::getId, lastId)
                    .orderByAsc(SmartWalletFillEntity::getId)
                    .last("limit " + pageSize));
            if (fills == null || fills.isEmpty()) {
                break;
            }
            for (SmartWalletFillEntity fill : fills) {
                total = total.add(safe(fill.getSize()));
                if (fill.getId() != null && fill.getId() > lastId) {
                    lastId = fill.getId();
                }
            }
            if (fills.size() < pageSize) {
                break;
            }
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private void executeFillAndAccounting(SmartWalletOrderEntity order,
                                          BigDecimal filledSize,
                                          BigDecimal avgPrice,
                                          BigDecimal tradingFeeUsdc,
                                          BigDecimal gasFeeUsdc,
                                          Long marketEndAt,
                                          Date now) {
        BigDecimal notional = filledSize.multiply(avgPrice);
        BigDecimal fee = totalFeeUsdc(tradingFeeUsdc, gasFeeUsdc);
        SmartWalletFillEntity fill = new SmartWalletFillEntity()
                .setOrderId(order.getId())
                .setPositionId(order.getPositionId())
                .setFollowTaskId(order.getFollowTaskId())
                .setWalletAddress(order.getWalletAddress())
                .setExecutionAddress(order.getExecutionAddress())
                .setMarketInfoId(order.getMarketInfoId())
                .setMarketInfo(order.getMarketInfo())
                .setSide(order.getSide())
                .setPrice(avgPrice)
                .setSize(filledSize)
                .setNotionalUsdc(notional)
                .setFeeUsdc(fee)
                .setTradingFeeUsdc(tradingFeeUsdc)
                .setFeePol(safe(order.getFeePol()))
                .setGasFeeUsdc(gasFeeUsdc)
                .setFilledAt(now)
                .setCreatedAt(new Date());
        fillMapper.insert(fill);

        PositionUpdateResult positionUpdate = applyFillToPosition(order, fill, marketEndAt);
        BigDecimal realizedDelta = positionUpdate == null ? BigDecimal.ZERO : safe(positionUpdate.realizedDelta());
        fill.setRealizedPnlUsdc(realizedDelta.setScale(8, RoundingMode.HALF_UP));
        syncOrderAndFillPositionLink(order, fill, positionUpdate.position());
        fillMapper.updateById(fill);
        appendCashLedger(order, fill, positionUpdate);
        updateOrderRealizedPnl(order, realizedDelta, now);
        rebuildDailyReport(order.getExecutionAddress(), toLocalDate(now));
        syncTradeOrder(positionUpdate == null ? null : positionUpdate.position());
        // 当 position 因 exit fill 关闭时，同步更新 follow_task 状态
        if (positionUpdate != null && positionUpdate.position() != null
                && "CLOSED".equalsIgnoreCase(positionUpdate.position().getStatus())) {
            markFollowTaskSettled(positionUpdate.position().getId());
        }
    }

    private SmartWalletFillEntity latestPositionFill(SmartWalletPositionEntity position) {
        if (position == null) {
            return null;
        }
        if (position.getId() != null) {
            List<SmartWalletFillEntity> linkedFills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                    .eq(SmartWalletFillEntity::getPositionId, position.getId())
                    .orderByDesc(SmartWalletFillEntity::getFilledAt)
                    .orderByDesc(SmartWalletFillEntity::getId)
                    .last("limit 1"));
            if (linkedFills != null && !linkedFills.isEmpty()) {
                return linkedFills.get(0);
            }
        }
        if (position.getMarketInfoId() != null) {
            List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                    .isNull(SmartWalletFillEntity::getPositionId)
                    .eq(SmartWalletFillEntity::getExecutionAddress, position.getExecutionAddress())
                    .eq(SmartWalletFillEntity::getMarketInfoId, position.getMarketInfoId())
                    .orderByDesc(SmartWalletFillEntity::getFilledAt)
                    .orderByDesc(SmartWalletFillEntity::getId)
                    .last("limit 1"));
            return fills == null || fills.isEmpty() ? null : fills.get(0);
        }
        return null;
    }

    private String buildExitOrderMessage(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "Paper position exited before oracle settlement.";
        }
        return "Paper position exited before oracle settlement. reason=" + reason.trim();
    }

    private String buildLiveExitFailureMessage(String reason, String message) {
        String normalizedMessage = StringUtils.hasText(message) ? message.trim() : "unknown failure";
        if (!StringUtils.hasText(reason)) {
            return normalizedMessage;
        }
        return "Live position exit failed. reason=" + reason.trim() + ", message=" + normalizedMessage;
    }

    public List<SmartWalletOrderView> listOrders(int limit) {
        return listOrders(limit, null);
    }

    /**
     * 兼容旧接口 —— 内部委托游标分页实现，不再全表扫描。
     */
    public List<SmartWalletOrderView> listOrders(int limit, String mode) {
        return listOrdersCursor(limit, null, mode).items();
    }

    /**
     * 游标分页查询订单列表。
     * <p>
     * 改进点：
     * 1. 将 mode 过滤下推到 SQL（通过 execution_address 条件），避免全表加载后 Java 层过滤。
     * 2. 基于 lastId（主键）做游标分页，深页不退化。
     *
     * @param limit   每页条数
     * @param lastId  上一页最后一条记录的 id，首页传 null
     * @param mode    PAPER / LIVE / null（全部）
     * @return 游标分页结果
     */
    public CursorPageView<SmartWalletOrderView> listOrdersCursor(int limit, Long lastId, String mode) {
        return listOrdersCursor(limit, lastId, mode, null, null, null, null, null);
    }

    public CursorPageView<SmartWalletOrderView> listOrdersCursor(int limit,
                                                                 Long lastId,
                                                                 String mode,
                                                                 String pnl,
                                                                 String settlement,
                                                                 String side,
                                                                 String status,
                                                                 String q) {
        int safeLimit = Math.max(limit, 1);
        // 多取一条用于判断 hasMore
        int fetchSize = safeLimit + 1;

        LambdaQueryWrapper<SmartWalletOrderEntity> wrapper = new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .orderByDesc(SmartWalletOrderEntity::getId)
                .last("limit " + fetchSize);

        // ── 将 mode 过滤下推到 SQL ──
        String normalized = normalizeDashboardMode(mode);
        if ("PAPER".equals(normalized)) {
            wrapper.eq(SmartWalletOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        } else if ("LIVE".equals(normalized)) {
            wrapper.ne(SmartWalletOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        }

        applyOrderCursorFilters(wrapper, pnl, settlement, side, status, q);

        // ── 游标条件：沿用 placedAt desc, id desc 的稳定排序翻页 ──
        if (lastId != null) {
            SmartWalletOrderEntity cursorOrder = orderMapper.selectById(lastId);
            if (cursorOrder != null && cursorOrder.getPlacedAt() != null) {
                Date cursorPlacedAt = cursorOrder.getPlacedAt();
                wrapper.and(condition -> condition
                        .lt(SmartWalletOrderEntity::getPlacedAt, cursorPlacedAt)
                        .or(nested -> nested
                                .eq(SmartWalletOrderEntity::getPlacedAt, cursorPlacedAt)
                                .lt(SmartWalletOrderEntity::getId, lastId)));
            } else {
                wrapper.lt(SmartWalletOrderEntity::getId, lastId);
            }
        }

        List<SmartWalletOrderEntity> orders = orderMapper.selectList(wrapper);
        marketInfoService.populateOrders(orders);
        List<SmartWalletOrderView> filtered = new ArrayList<>();
        for (SmartWalletOrderEntity order : orders) {
            SmartWalletOrderView view = toOrderView(order);
            if (matchesOrderViewFilters(view, pnl)) {
                filtered.add(view);
            }
        }
        boolean hasMore = orders.size() > safeLimit;
        List<SmartWalletOrderView> items = filtered.size() > safeLimit ? filtered.subList(0, safeLimit) : filtered;
        String nextCursor = hasMore && !orders.isEmpty()
                ? String.valueOf(orders.get(Math.min(orders.size(), safeLimit) - 1).getId())
                : null;

        return CursorPageView.of(items, nextCursor);
    }

    public CursorPageView<SmartWalletTradeView> listTradesCursor(int limit,
                                                                 Long lastId,
                                                                 String mode,
                                                                 String pnl,
                                                                 String settlement,
                                                                 String status,
                                                                 String q) {
        int safeLimit = Math.max(limit, 1);
        int fetchSize = safeLimit + 1;
        LambdaQueryWrapper<SmartWalletPositionEntity> wrapper = new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getOpenedAt)
                .orderByDesc(SmartWalletPositionEntity::getId)
                .last("limit " + fetchSize);

        String normalizedMode = normalizeDashboardMode(mode);
        if ("PAPER".equals(normalizedMode)) {
            wrapper.eq(SmartWalletPositionEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        } else if ("LIVE".equals(normalizedMode)) {
            wrapper.ne(SmartWalletPositionEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SmartWalletPositionEntity::getStatus, status.trim());
        } else {
            applyTradeSettlementSqlFilter(wrapper, settlement);
        }
        if (lastId != null) {
            SmartWalletPositionEntity cursor = positionMapper.selectById(lastId);
            if (cursor != null && cursor.getOpenedAt() != null) {
                Date cursorOpenedAt = cursor.getOpenedAt();
                wrapper.and(condition -> condition
                        .lt(SmartWalletPositionEntity::getOpenedAt, cursorOpenedAt)
                        .or(nested -> nested
                                .eq(SmartWalletPositionEntity::getOpenedAt, cursorOpenedAt)
                                .lt(SmartWalletPositionEntity::getId, lastId)));
            } else {
                wrapper.lt(SmartWalletPositionEntity::getId, lastId);
            }
        }

        List<SmartWalletPositionEntity> positions = positionMapper.selectList(wrapper);
        marketInfoService.populatePositions(positions);
        List<SmartWalletTradeView> filtered = new ArrayList<>();
        for (SmartWalletPositionEntity position : positions) {
            SmartWalletTradeView view = toTradeView(position);
            if (matchesTradeFilters(view, pnl, settlement, q)) {
                filtered.add(view);
            }
        }
        boolean hasMore = positions.size() > safeLimit;
        List<SmartWalletTradeView> page = filtered.size() > safeLimit ? filtered.subList(0, safeLimit) : filtered;
        String nextCursor = hasMore && !positions.isEmpty()
                ? String.valueOf(positions.get(Math.min(positions.size(), safeLimit) - 1).getId())
                : null;
        return CursorPageView.of(page, nextCursor);
    }

    private void applyOrderCursorFilters(LambdaQueryWrapper<SmartWalletOrderEntity> wrapper,
                                         String pnl,
                                         String settlement,
                                         String side,
                                         String status,
                                         String q) {
        // PnL filters are applied after mapping to SmartWalletOrderView because
        // display PnL may be derived from linked positions or exit orders.

        String normalizedSettlement = normalizeFilterValue(settlement);
        if ("SETTLED".equals(normalizedSettlement)) {
            wrapper.in(SmartWalletOrderEntity::getStatus, List.of(ORDER_STATUS_SETTLED_WIN, ORDER_STATUS_SETTLED_LOSS));
        } else if ("UNSETTLED".equals(normalizedSettlement)) {
            wrapper.notIn(SmartWalletOrderEntity::getStatus, List.of(ORDER_STATUS_SETTLED_WIN, ORDER_STATUS_SETTLED_LOSS));
        } else if ("EARLY_EXIT".equals(normalizedSettlement)) {
            wrapper.and(condition -> condition
                    .isNotNull(SmartWalletOrderEntity::getExitReason)
                    .or()
                    .eq(SmartWalletOrderEntity::getOrderType, ORDER_TYPE_EXIT));
        }

        if (StringUtils.hasText(side)) {
            wrapper.eq(SmartWalletOrderEntity::getSide, side.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SmartWalletOrderEntity::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(q)) {
            String keyword = q.trim();
            wrapper.and(condition -> condition
                    .like(SmartWalletOrderEntity::getExternalOrderId, keyword)
                    .or()
                    .like(SmartWalletOrderEntity::getExecutionAddress, keyword)
                    .or()
                    .like(SmartWalletOrderEntity::getWalletAddress, keyword)
                    .or()
                    .apply("market_info_id IN (SELECT id FROM smart_wallet_market_info WHERE market_id LIKE {0} OR question LIKE {0} OR outcome LIKE {0})", "%" + keyword + "%")
                    .or()
                    .apply("CAST(id AS CHAR) LIKE {0}", "%" + keyword + "%"));
        }
    }

    private boolean matchesOrderViewFilters(SmartWalletOrderView view, String pnl) {
        String normalizedPnl = normalizeFilterValue(pnl);
        BigDecimal rowPnl = safe(view == null ? null : view.getRealizedPnlUsdc());
        if ("PROFIT".equals(normalizedPnl)) {
            return rowPnl.compareTo(BigDecimal.ZERO) > 0;
        }
        if ("LOSS".equals(normalizedPnl)) {
            return rowPnl.compareTo(BigDecimal.ZERO) < 0;
        }
        if ("ZERO".equals(normalizedPnl)) {
            return rowPnl.compareTo(BigDecimal.ZERO) == 0;
        }
        return true;
    }

    public List<SmartWalletFillView> listFills(int limit) {
        return listFills(limit, null);
    }

    public List<SmartWalletFillView> listFills(int limit, String mode) {
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .orderByDesc(SmartWalletFillEntity::getFilledAt)
                .last("limit " + Math.max(limit, 1)));
        marketInfoService.populateFills(fills);
        List<SmartWalletFillView> result = new ArrayList<>();
        for (SmartWalletFillEntity fill : fills) {
            if (!matchesDashboardMode(fill.getExecutionAddress(), mode)) {
                continue;
            }
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
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)
                    || !marketStateService.isActiveLifecycle(position.getMarketLifecycle())) {
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
        List<SmartWalletPositionView> result = new ArrayList<>(loadPositionsByStatuses(List.of(
                        "OPEN",
                        "PARTIALLY_CLOSED",
                        POSITION_STATUS_SETTLEMENT_PENDING_ORACLE,
                        "RESOLVED_UNCLAIMED"
                )).stream()
                .filter(position -> "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())
                        || POSITION_STATUS_SETTLEMENT_PENDING_ORACLE.equalsIgnoreCase(position.getStatus())
                        || !marketStateService.isActiveLifecycle(position.getMarketLifecycle()))
                .filter(position -> matchesDashboardMode(position.getExecutionAddress(), mode))
                .map(this::toPositionView)
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
        marketInfoService.populatePositions(positions);
        refreshPositionMarketStates(positions);
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
        marketInfoService.populatePositions(positions);
        refreshPositionMarketStates(positions);
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
        marketInfoService.populate(position);
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
                    .setAsset(ASSET_USDC_E)
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

        position = updatePositionWithRetry(position.getId(), p -> {
            p.setStatus("CLAIMED");
        }, "claimResolvedPosition", 3);
        syncTradeOrder(position);
        markFollowTaskSettled(position.getId());

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
        long now = System.currentTimeMillis();
        String normalizedMode = mode == null ? "" : mode;
        if (cachedPnlSummary != null && now < cachedPnlSummaryExpiresAt
                && normalizedMode.equals(cachedPnlSummaryMode == null ? "" : cachedPnlSummaryMode)) {
            return cachedPnlSummary;
        }
        SmartWalletPnlSummaryView result = buildPnlSummary(mode);
        cachedPnlSummary = result;
        cachedPnlSummaryMode = normalizedMode;
        cachedPnlSummaryExpiresAt = now + PNL_CACHE_TTL_MS;
        return result;
    }

    private SmartWalletPnlSummaryView buildPnlSummary(String mode) {
        // SQL aggregation for fills count, volume, fees, realized PnL
        String normalizedMode = normalizeDashboardMode(mode);
        int filledOrders = fillMapper.countByMode(normalizedMode);
        BigDecimal volume = safe(fillMapper.sumVolumeByMode(normalizedMode));
        BigDecimal fee = totalFeeAmountByMode(mode, null, null);
        BigDecimal realized = sumLedgerSignedAmountByMode("REALIZED_PNL", mode, null, null);

        // Positions still require Java-side iteration for markToMarket & pending settlement logic
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        marketInfoService.populatePositions(positions);
        int wins = 0;
        int completedPositions = 0;
        int openPositions = 0;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal pendingSettlement = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            markToMarket(position);
            if (isPendingSettlementTracked(position)) {
                pendingSettlement = pendingSettlement.add(pendingSettlementNetPnlForPosition(position));
            } else if (isActivePositionStatus(position.getStatus())) {
                unrealized = unrealized.add(safe(position.getUnrealizedPnlUsdc()));
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
        BigDecimal composite = settledNet.add(unrealized).add(pendingSettlement);
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
                .netPnlUsdc(composite)
                .winRate(winRate)
                .build();
    }

    public SmartWalletCapacitySummaryView capacitySummary() {
        return capacitySummary(null);
    }

    public SmartWalletCapacitySummaryView capacitySummary(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, SETTLEMENT_CANDIDATE_POSITION_STATUSES));
        marketInfoService.populatePositions(positions);
        BigDecimal totalOpenCost = BigDecimal.ZERO;
        int filteredOpenPositionCount = 0;
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            if (isCapitalLockedPositionStatus(position.getStatus())) {
                filteredOpenPositionCount++;
                totalOpenCost = totalOpenCost.add(safe(position.getCostBasisUsdc()));
            }
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
        marketInfoService.populatePositions(positions);
        BigDecimal realized = sumLedgerSignedAmount("REALIZED_PNL", executionAddress, null);
        BigDecimal fees = sumLedgerAmount("FEE", executionAddress, null).add(sumLedgerAmount("NETWORK_FEE", executionAddress, null));
        BigDecimal openCost = BigDecimal.ZERO;
        BigDecimal lockedClaimProceeds = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            if (isCapitalLockedPositionStatus(position.getStatus())) {
                openCost = openCost.add(safe(position.getCostBasisUsdc()));
            }
            lockedClaimProceeds = lockedClaimProceeds.add(lockedClaimProceedsForPosition(position));
        }
        BigDecimal budget = safe(properties.getSignalRisk().getMaxTotalOpenCostUsdc());
        return budget.add(realized).subtract(fees).subtract(openCost).subtract(lockedClaimProceeds).setScale(8, RoundingMode.HALF_UP);
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
        marketInfoService.populatePositions(positions);
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
        long now = System.currentTimeMillis();
        String normalizedMode = mode == null ? "" : mode;
        if (cachedPerformance != null && now < cachedPerformanceExpiresAt
                && normalizedMode.equals(cachedPerformanceMode == null ? "" : cachedPerformanceMode)) {
            return cachedPerformance;
        }
        SmartWalletPerformanceView result = buildPerformance(mode);
        cachedPerformance = result;
        cachedPerformanceMode = normalizedMode;
        cachedPerformanceExpiresAt = now + PNL_CACHE_TTL_MS;
        return result;
    }

    private SmartWalletPerformanceView buildPerformance(String mode) {
        String normalizedMode = normalizeDashboardMode(mode);
        // SQL aggregation for fills count and volume
        int filledOrders = fillMapper.countByMode(normalizedMode);
        BigDecimal volume = safe(fillMapper.sumVolumeByMode(normalizedMode));
        BigDecimal fee = totalFeeAmountByMode(mode, null, null);
        BigDecimal realized = sumLedgerSignedAmountByMode("REALIZED_PNL", mode, null, null);

        // Orders and fills still needed for exit order summary
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        marketInfoService.populateFills(fills);
        marketInfoService.populateOrders(orders);
        marketInfoService.populatePositions(positions);
        List<SmartWalletPositionEntity> filteredPositions = positions.stream()
                .filter(position -> matchesDashboardMode(position.getExecutionAddress(), mode))
                .toList();
        ConflictSummary conflictSummary = summarizeConflicts(filteredPositions, null);
        ExitOrderSummary exitOrderSummary = summarizeExitOrders(orders, fills, mode);
        int openPositions = 0;
        int pendingPositions = 0;
        int closedPositions = 0;
        int winningPositions = 0;
        int losingPositions = 0;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal pendingSettlement = BigDecimal.ZERO;
        BigDecimal totalWins = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : filteredPositions) {
            markToMarket(position);
            BigDecimal positionRealized = realizedPnlForPosition(position);
            boolean pendingTracked = isPendingSettlementTracked(position);
            if (pendingTracked) {
                pendingPositions++;
                pendingSettlement = pendingSettlement.add(pendingSettlementNetPnlForPosition(position));
            } else if (isActivePositionStatus(position.getStatus())) {
                unrealized = unrealized.add(safe(position.getUnrealizedPnlUsdc()));
                openPositions++;
            } else if (isClosedOrSettledStatus(position.getStatus())) {
                closedPositions++;
            }
            if (pendingTracked || isClosedOrSettledStatus(position.getStatus())) {
                if (positionRealized.compareTo(BigDecimal.ZERO) > 0) {
                    winningPositions++;
                    totalWins = totalWins.add(positionRealized);
                }
                if (positionRealized.compareTo(BigDecimal.ZERO) < 0) {
                    losingPositions++;
                    totalLosses = totalLosses.add(positionRealized.abs());
                }
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
        BigDecimal composite = settledNet.add(unrealized).add(pendingSettlement);
        return SmartWalletPerformanceView.builder()
                .totalOrders(countOrdersByMode(mode))
                .filledOrders(filledOrders)
                .closedPositions(closedPositions)
                .openPositions(openPositions)
                .pendingPositions(pendingPositions)
                .winningPositions(winningPositions)
                .losingPositions(losingPositions)
                .winRate(winRate)
                .tradedVolumeUsdc(volume)
                .feeUsdc(fee)
                .realizedPnlUsdc(realized)
                .unrealizedPnlUsdc(unrealized)
                .pendingSettlementPnlUsdc(pendingSettlement)
                .compositePnlUsdc(composite)
                .netPnlUsdc(composite)
                .averageWinUsdc(averageWin)
                .averageLossUsdc(averageLoss)
                .profitFactor(profitFactor)
                .maxDrawdownUsdc(maxDrawdown)
                .conflictingMarkets(conflictSummary.conflictingMarkets())
                .conflictingPositions(conflictSummary.conflictingPositions())
                .openConflictingMarkets(conflictSummary.openConflictingMarkets())
                .openConflictingPositions(conflictSummary.openConflictingPositions())
                .conflictNetPnlUsdc(conflictSummary.conflictNetPnlUsdc())
                .earlyExitOrderCount(exitOrderSummary.earlyExitOrderCount())
                .earlyExitRealizedPnlUsdc(exitOrderSummary.earlyExitRealizedPnlUsdc())
                .takeProfitOrderCount(exitOrderSummary.takeProfitOrderCount())
                .takeProfitRealizedPnlUsdc(exitOrderSummary.takeProfitRealizedPnlUsdc())
                .stopLossOrderCount(exitOrderSummary.stopLossOrderCount())
                .stopLossRealizedPnlUsdc(exitOrderSummary.stopLossRealizedPnlUsdc())
                .build();
    }

    public List<SmartWalletPnlTimelinePoint> pnlTimeline(int days) {
        return pnlTimeline(days, null);
    }

    public List<SmartWalletPnlTimelinePoint> pnlTimeline(int days, String mode) {
        return pnlTimeline(days, 24, mode);
    }

    public List<SmartWalletPnlTimelinePoint> pnlTimeline(int days, int bucketHours, String mode) {
        int safeDays = Math.max(Math.min(days, 180), 1);
        int safeBucketHours = normalizeTimelineBucketHours(bucketHours);
        if (safeBucketHours >= 24) {
            return dailyReportTimeline(safeDays, mode);
        }
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zoneId);
        LocalDateTime requestedStartBucket = floorToTimelineBucket(now.minusDays(safeDays), safeBucketHours);
        Date startDate = Date.from(requestedStartBucket.atZone(zoneId).toInstant());

        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .in(SmartWalletCashLedgerEntity::getEntryType, List.of("REALIZED_PNL", "FEE", "NETWORK_FEE"))
                .ge(SmartWalletCashLedgerEntity::getOccurredAt, startDate)
                .le(SmartWalletCashLedgerEntity::getOccurredAt, Date.from(now.atZone(zoneId).toInstant()))
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        if (entries == null || entries.isEmpty()) {
            return dailyReportTimeline(safeDays, mode);
        }

        List<TimelineLedgerEntry> timelineEntries = new ArrayList<>();
        LocalDateTime firstDataBucket = null;
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (entry == null || entry.getOccurredAt() == null || !matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            BigDecimal delta = ledgerPnlDelta(entry);
            if (delta.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            LocalDateTime bucketAt = floorToTimelineBucket(LocalDateTime.ofInstant(entry.getOccurredAt().toInstant(), zoneId), safeBucketHours);
            if (firstDataBucket == null) {
                firstDataBucket = bucketAt;
            }
            timelineEntries.add(new TimelineLedgerEntry(entry, bucketAt, delta));
        }
        if (timelineEntries.isEmpty() || firstDataBucket == null) {
            return dailyReportTimeline(safeDays, mode);
        }

        Map<LocalDateTime, TimelineBucketAccumulator> buckets = new LinkedHashMap<>();
        for (LocalDateTime cursor = firstDataBucket; !cursor.isAfter(now); cursor = cursor.plusHours(safeBucketHours)) {
            buckets.put(cursor, new TimelineBucketAccumulator());
        }

        for (TimelineLedgerEntry entry : timelineEntries) {
            TimelineBucketAccumulator bucket = buckets.get(entry.bucketAt());
            if (bucket == null) {
                continue;
            }
            bucket.add(entry.entity(), entry.delta());
        }

        List<SmartWalletPnlTimelinePoint> points = new ArrayList<>();
        TimelineBaseline baseline = timelineBaselineBefore(startDate, mode);
        BigDecimal cumulativeLoss = baseline.cumulativeLossPnlUsdc();
        BigDecimal cumulativeRealized = baseline.cumulativeRealizedPnlUsdc();
        BigDecimal cumulativeFee = baseline.cumulativeFeeUsdc();
        BigDecimal cumulativeNet = baseline.cumulativeNetPnlUsdc();
        for (Map.Entry<LocalDateTime, TimelineBucketAccumulator> entry : buckets.entrySet()) {
            TimelineBucketAccumulator bucket = entry.getValue();
            cumulativeLoss = cumulativeLoss.add(bucket.lossPnl());
            cumulativeRealized = cumulativeRealized.add(bucket.realizedPnl);
            cumulativeFee = cumulativeFee.add(bucket.fee);
            cumulativeNet = cumulativeNet.add(bucket.netPnl());
            points.add(SmartWalletPnlTimelinePoint.builder()
                    .bucketAt(Date.from(entry.getKey().atZone(zoneId).toInstant()))
                    .profitPnlUsdc(bucket.profitPnl().setScale(8, RoundingMode.HALF_UP))
                    .lossPnlUsdc(bucket.lossPnl().setScale(8, RoundingMode.HALF_UP))
                    .realizedPnlUsdc(bucket.realizedPnl.setScale(8, RoundingMode.HALF_UP))
                    .feeUsdc(bucket.fee.setScale(8, RoundingMode.HALF_UP))
                    .netPnlUsdc(bucket.netPnl().setScale(8, RoundingMode.HALF_UP))
                    .cumulativeLossPnlUsdc(cumulativeLoss.setScale(8, RoundingMode.HALF_UP))
                    .cumulativeRealizedPnlUsdc(cumulativeRealized.setScale(8, RoundingMode.HALF_UP))
                    .cumulativeFeeUsdc(cumulativeFee.setScale(8, RoundingMode.HALF_UP))
                    .cumulativeNetPnlUsdc(cumulativeNet.setScale(8, RoundingMode.HALF_UP))
                    .build());
        }
        return points;
    }

    private List<SmartWalletPnlTimelinePoint> dailyReportTimeline(int days, String mode) {
        int safeDays = Math.max(days, 1);
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zoneId);
        TimelineBucketAccumulator currentDayBucket = currentDayTimelineBucket(today, zoneId, mode);
        BigDecimal currentFloatingPnl = currentFloatingPnl(mode);
        boolean hasRealtimeToday = currentDayBucket.hasActivity() || currentFloatingPnl.compareTo(BigDecimal.ZERO) != 0;

        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByAsc(SmartWalletDailyReportEntity::getReportDate));
        if (reports == null) {
            reports = List.of();
        }
        List<SmartWalletDailyReportEntity> filteredReports = reports.stream()
                .filter(report -> matchesDashboardMode(report.getExecutionAddress(), mode))
                .toList();
        int fromIndex = Math.max(filteredReports.size() - safeDays, 0);
        List<SmartWalletDailyReportEntity> selectedReports = filteredReports.subList(fromIndex, filteredReports.size());
        Map<LocalDate, DailyOutcomeBreakdown> outcomeBreakdowns = loadDailyOutcomeBreakdowns(selectedReports, mode);
        List<SmartWalletPnlTimelinePoint> points = new ArrayList<>();
        TimelineBaseline baseline = dailyTimelineBaseline(selectedReports, today, zoneId, mode);
        BigDecimal cumulativeLoss = baseline.cumulativeLossPnlUsdc();
        BigDecimal cumulativeRealized = baseline.cumulativeRealizedPnlUsdc();
        BigDecimal cumulativeFee = baseline.cumulativeFeeUsdc();
        BigDecimal cumulative = baseline.cumulativeNetPnlUsdc();
        BigDecimal previousFloatingPnl = BigDecimal.ZERO;
        boolean emittedToday = false;
        for (SmartWalletDailyReportEntity report : selectedReports) {
            LocalDate reportDate = toLocalDate(report.getReportDate());
            boolean useRealtimeToday = hasRealtimeToday && today.equals(reportDate);
            BigDecimal realized = useRealtimeToday ? currentDayBucket.realizedPnl : safe(report.getRealizedPnlUsdc());
            BigDecimal fee = useRealtimeToday ? currentDayBucket.fee : safe(report.getFeeUsdc());
            BigDecimal floatingPnl = useRealtimeToday ? currentFloatingPnl : safe(report.getUnrealizedPnlUsdc());
            BigDecimal net = realized.subtract(fee).add(floatingPnl.subtract(previousFloatingPnl));
            DailyOutcomeBreakdown breakdown = outcomeBreakdowns.get(reportDate);
            BigDecimal profit = useRealtimeToday
                    ? currentDayBucket.profitPnl()
                    : (breakdown != null && breakdown.hasOutcomeEntries()
                    ? breakdown.profitPnlUsdc
                    : realized.max(BigDecimal.ZERO));
            BigDecimal loss = useRealtimeToday
                    ? currentDayBucket.lossPnl()
                    : (breakdown != null && breakdown.hasOutcomeEntries()
                    ? breakdown.lossPnlUsdc
                    : (realized.compareTo(BigDecimal.ZERO) < 0 ? realized : BigDecimal.ZERO));
            cumulativeLoss = cumulativeLoss.add(loss);
            cumulativeRealized = cumulativeRealized.add(realized);
            cumulativeFee = cumulativeFee.add(fee);
            cumulative = cumulativeRealized.subtract(cumulativeFee).add(floatingPnl);
            points.add(SmartWalletPnlTimelinePoint.builder()
                    .bucketAt(report.getReportDate())
                    .profitPnlUsdc(profit)
                    .lossPnlUsdc(loss)
                    .realizedPnlUsdc(realized)
                    .feeUsdc(fee)
                    .netPnlUsdc(net)
                    .cumulativeLossPnlUsdc(cumulativeLoss)
                    .cumulativeRealizedPnlUsdc(cumulativeRealized)
                    .cumulativeFeeUsdc(cumulativeFee)
                    .cumulativeNetPnlUsdc(cumulative)
                    .build());
            previousFloatingPnl = floatingPnl;
            emittedToday = emittedToday || useRealtimeToday;
        }
        if (hasRealtimeToday && !emittedToday) {
            BigDecimal realized = currentDayBucket.realizedPnl;
            BigDecimal fee = currentDayBucket.fee;
            BigDecimal floatingPnl = currentFloatingPnl;
            BigDecimal net = realized.subtract(fee).add(floatingPnl.subtract(previousFloatingPnl));
            cumulativeLoss = cumulativeLoss.add(currentDayBucket.lossPnl());
            cumulativeRealized = cumulativeRealized.add(realized);
            cumulativeFee = cumulativeFee.add(fee);
            cumulative = cumulativeRealized.subtract(cumulativeFee).add(floatingPnl);
            points.add(SmartWalletPnlTimelinePoint.builder()
                    .bucketAt(java.sql.Date.valueOf(today))
                    .profitPnlUsdc(currentDayBucket.profitPnl())
                    .lossPnlUsdc(currentDayBucket.lossPnl())
                    .realizedPnlUsdc(realized)
                    .feeUsdc(fee)
                    .netPnlUsdc(net)
                    .cumulativeLossPnlUsdc(cumulativeLoss)
                    .cumulativeRealizedPnlUsdc(cumulativeRealized)
                    .cumulativeFeeUsdc(cumulativeFee)
                    .cumulativeNetPnlUsdc(cumulative)
                    .build());
        }
        return points;
    }

    private int normalizeTimelineBucketHours(Integer bucketHours) {
        if (bucketHours == null) {
            return 24;
        }
        return switch (bucketHours) {
            case 8, 12, 24, 72 -> bucketHours;
            default -> 24;
        };
    }

    private LocalDateTime floorToTimelineBucket(LocalDateTime value, int bucketHours) {
        if (bucketHours >= 24) {
            int bucketDays = Math.max(bucketHours / 24, 1);
            LocalDate epoch = LocalDate.of(1970, 1, 1);
            long daysSinceEpoch = ChronoUnit.DAYS.between(epoch, value.toLocalDate());
            long bucketStartDays = Math.floorDiv(daysSinceEpoch, bucketDays) * (long) bucketDays;
            return epoch.plusDays(bucketStartDays).atStartOfDay();
        }
        int hour = Math.floorDiv(value.getHour(), bucketHours) * bucketHours;
        return value.withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    public List<SmartWalletDailyReportView> listDailyReports(int limit) {
        return listDailyReports(limit, null);
    }

    public List<SmartWalletDailyReportView> listDailyReports(int limit, String mode) {
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByDesc(SmartWalletDailyReportEntity::getReportDate));
        if (reports == null) {
            reports = List.of();
        }
        List<SmartWalletDailyReportView> result = new ArrayList<>();
        int safeLimit = Math.max(limit, 1);
        List<SmartWalletDailyReportEntity> selectedReports = new ArrayList<>();
        for (SmartWalletDailyReportEntity report : reports) {
            if (!matchesDashboardMode(report.getExecutionAddress(), mode)) {
                continue;
            }
            selectedReports.add(report);
            if (selectedReports.size() >= safeLimit) {
                break;
            }
        }
        Map<LocalDate, DailyOutcomeBreakdown> outcomeBreakdowns = loadDailyOutcomeBreakdowns(selectedReports, mode);
        for (SmartWalletDailyReportEntity report : selectedReports) {
            result.add(toDailyReportView(report, outcomeBreakdowns.get(toLocalDate(report.getReportDate()))));
        }
        return result;
    }

    public List<SmartWalletReportAttributionItem> walletAttribution(int limit) {
        return walletAttribution(limit, null);
    }

    public List<SmartWalletReportAttributionItem> walletAttribution(int limit, String mode) {
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .in(SmartWalletOrderEntity::getStatus, EXECUTED_ORDER_STATUSES));
        List<SmartWalletCashLedgerEntity> ledgerEntries = cashLedgerMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        marketInfoService.populateOrders(orders);
        marketInfoService.populatePositions(positions);
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
                .in(SmartWalletOrderEntity::getStatus, EXECUTED_ORDER_STATUSES));
        List<SmartWalletCashLedgerEntity> ledgerEntries = cashLedgerMapper.selectList(new LambdaQueryWrapper<>());
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        marketInfoService.populateOrders(orders);
        marketInfoService.populatePositions(positions);
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

    public List<SmartWalletFollowAddressDailyReportView> followAddressDailyReport(LocalDate reportDate, int limit) {
        return followAddressDailyReport(reportDate, limit, null);
    }

    public List<SmartWalletFollowAddressDailyReportView> followAddressDailyReport(LocalDate reportDate, int limit, String mode) {
        LocalDate targetDate = reportDate == null ? LocalDate.now(ZoneId.systemDefault()) : reportDate;
        Date start = Date.from(targetDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date end = Date.from(targetDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        boolean currentDay = targetDate.equals(LocalDate.now(ZoneId.systemDefault()));

        List<SmartWalletOrderEntity> allOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .orderByDesc(SmartWalletOrderEntity::getPlacedAt)
                .orderByDesc(SmartWalletOrderEntity::getId));
        List<SmartWalletFillEntity> allFills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .orderByDesc(SmartWalletFillEntity::getFilledAt)
                .orderByDesc(SmartWalletFillEntity::getId));
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        marketInfoService.populateOrders(allOrders);
        marketInfoService.populateFills(allFills);
        marketInfoService.populatePositions(positions);

        Map<String, String> positionWalletMap = buildPositionWalletMap(allOrders, allFills);
        Map<String, FollowAddressDailyAccumulator> grouped = new LinkedHashMap<>();

        for (SmartWalletOrderEntity order : allOrders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode)
                    || !isWithin(order.getPlacedAt(), start, end)) {
                continue;
            }
            FollowAddressDailyAccumulator acc = grouped.computeIfAbsent(
                    normalizeWalletAddress(order.getWalletAddress()),
                    FollowAddressDailyAccumulator::new
            );
            acc.addOrder(order);
        }

        for (SmartWalletFillEntity fill : allFills) {
            if (!matchesDashboardMode(fill.getExecutionAddress(), mode)
                    || !isWithin(fill.getFilledAt(), start, end)) {
                continue;
            }
            FollowAddressDailyAccumulator acc = grouped.computeIfAbsent(
                    normalizeWalletAddress(fill.getWalletAddress()),
                    FollowAddressDailyAccumulator::new
            );
            acc.addFill(fill);
        }

        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            String walletAddress = resolvePositionWalletAddress(position, positionWalletMap);
            FollowAddressDailyAccumulator acc = grouped.computeIfAbsent(walletAddress, FollowAddressDailyAccumulator::new);
            if (position.getClosedAt() != null && targetDate.equals(toLocalDate(position.getClosedAt()))) {
                acc.addClosedPosition(position);
            }
            if (currentDay && isActivePositionStatus(position.getStatus())) {
                markToMarket(position);
                acc.addOpenPosition(position);
            }
        }

        return grouped.values().stream()
                .sorted(Comparator.comparing(FollowAddressDailyAccumulator::netPnl).reversed())
                .limit(Math.max(limit, 1))
                .map(acc -> acc.toView(java.sql.Date.valueOf(targetDate)))
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

    public int backfillHistoricalCostBasis() {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .orderByAsc(SmartWalletPositionEntity::getOpenedAt)
                .orderByAsc(SmartWalletPositionEntity::getId));
        marketInfoService.populatePositions(positions);
        int rebuilt = 0;
        for (SmartWalletPositionEntity position : positions) {
            List<SmartWalletFillEntity> positionFills = loadPositionFills(position);
            if (positionFills.isEmpty()) {
                continue;
            }
            PositionSnapshot snapshot = replayPositionFromFills(positionFills);
            position.setNetSide(snapshot.quantity().compareTo(BigDecimal.ZERO) > 0 ? "BUY" : "FLAT")
                    .setQuantity(snapshot.quantity())
                    .setAverageEntryPrice(snapshot.averageEntryPrice())
                    .setCostBasisUsdc(snapshot.costBasisUsdc())
                    .setOriginalCostBasisUsdc(snapshot.quantity().compareTo(BigDecimal.ZERO) == 0
                            ? originalCostBasisForPosition(position)
                            : snapshot.costBasisUsdc())
                    .setFeePolTotal(totalFeePolFromFills(positionFills))
                    .setRealizedPnlUsdc(snapshot.realizedPnlUsdc())
                    .setLastMarkPrice(snapshot.lastPrice())
                    .setOpenedAt(snapshot.openedAt())
                    .setLastFilledAt(snapshot.lastFilledAt())
                    .setClosedAt(snapshot.quantity().compareTo(BigDecimal.ZERO) == 0 ? snapshot.lastFilledAt() : null)
                    .setStatus(snapshot.status())
                    .setUpdatedAt(new Date());
            if (updatePositionChecked(position, "rebuildPositionFromFills")) {
                if (position.getLastFilledAt() != null) {
                    rebuildDailyReport(position.getExecutionAddress(), toLocalDate(position.getLastFilledAt()));
                }
                syncTradeOrder(position);
                rebuilt++;
            }
        }
        return rebuilt;
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
                    .tradingFeeUsdc(BigDecimal.ZERO)
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

    public Map<String, Object> backfillMissingTokenAndPositionLinks(int limit, boolean refreshCache) {
        int safeLimit = Math.max(limit, 1);
        int updatedOrders = backfillOrderTokenAndPositionIds(safeLimit, refreshCache);
        int updatedFills = backfillFillTokenAndPositionIds(safeLimit, refreshCache);
        int updatedPositions = backfillPositionTokenIds(safeLimit, refreshCache);
        return Map.of(
                "scannedLimit", safeLimit,
                "updatedOrders", updatedOrders,
                "updatedFills", updatedFills,
                "updatedPositions", updatedPositions,
                "totalUpdated", updatedOrders + updatedFills + updatedPositions
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
            Long miId = marketInfoService.findIdByMarketIdAndOutcome(item.getMarketId(), item.getOutcome());
            if (miId == null) {
                continue;
            }
            List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                    .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")
                    .eq(SmartWalletPositionEntity::getMarketInfoId, miId));
            marketInfoService.populatePositions(positions);
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
                .in(SmartWalletPositionEntity::getStatus, SETTLEMENT_CANDIDATE_POSITION_STATUSES)
                .orderByAsc(SmartWalletPositionEntity::getLastFilledAt)
                .last("limit " + Math.max(limit, 1)));
        marketInfoService.populatePositions(openPaperPositions);
        SettlementSyncSummary syncSummary = syncSettlementsForPositions(openPaperPositions, Math.max(limit, 1), refreshCache);

        List<SmartWalletPositionEntity> unclaimedPaperPositions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, "paper-book")
                .eq(SmartWalletPositionEntity::getStatus, "RESOLVED_UNCLAIMED")
                .orderByAsc(SmartWalletPositionEntity::getClosedAt)
                .last("limit " + Math.max(limit, 1)));
        marketInfoService.populatePositions(unclaimedPaperPositions);
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
                .in(SmartWalletPositionEntity::getStatus, SETTLEMENT_CANDIDATE_POSITION_STATUSES)
                .orderByAsc(SmartWalletPositionEntity::getLastFilledAt)
                .last("limit " + Math.max(positionLimit, 1)));
        marketInfoService.populatePositions(positions);
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
                    .oraclePendingMarkets(0)
                    .skippedFutureMarkets(0)
                    .failedMarkets(0)
                .build();
        }
        List<Long> allMarketInfoIds = new ArrayList<>();
        for (String mid : marketIds) {
            allMarketInfoIds.addAll(marketInfoService.findIdsByMarketId(mid));
        }
        if (allMarketInfoIds.isEmpty()) {
            return SettlementSyncSummary.builder()
                    .scannedPositions(0)
                    .distinctMarkets(0)
                    .fetchedMarkets(0)
                    .resolvedPositions(0)
                    .unresolvedMarkets(0)
                    .oraclePendingMarkets(0)
                    .skippedFutureMarkets(0)
                    .failedMarkets(0)
                    .build();
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, SETTLEMENT_CANDIDATE_POSITION_STATUSES)
                .in(SmartWalletPositionEntity::getMarketInfoId, allMarketInfoIds)
                .orderByAsc(SmartWalletPositionEntity::getLastFilledAt));
        marketInfoService.populatePositions(positions);
        return syncSettlementsForPositions(positions, marketIds.size(), refreshCache);
    }

    public SettlementSyncSummary confirmLocalMarketsByFinalSignals(List<String> marketIds, boolean refreshCache) {
        if (marketIds == null || marketIds.isEmpty()) {
            return SettlementSyncSummary.builder()
                    .scannedPositions(0)
                    .distinctMarkets(0)
                    .fetchedMarkets(0)
                    .resolvedPositions(0)
                    .unresolvedMarkets(0)
                    .oraclePendingMarkets(0)
                    .skippedFutureMarkets(0)
                    .failedMarkets(0)
                    .notes(List.of("No local settlement markets were provided for manual confirmation."))
                    .build();
        }
        List<Long> allMarketInfoIds = new ArrayList<>();
        for (String mid : marketIds) {
            allMarketInfoIds.addAll(marketInfoService.findIdsByMarketId(mid));
        }
        if (allMarketInfoIds.isEmpty()) {
            return SettlementSyncSummary.builder()
                    .scannedPositions(0)
                    .distinctMarkets(0)
                    .fetchedMarkets(0)
                    .resolvedPositions(0)
                    .unresolvedMarkets(0)
                    .oraclePendingMarkets(0)
                    .skippedFutureMarkets(0)
                    .failedMarkets(0)
                    .build();
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .in(SmartWalletPositionEntity::getStatus, SETTLEMENT_CANDIDATE_POSITION_STATUSES)
                .in(SmartWalletPositionEntity::getMarketInfoId, allMarketInfoIds)
                .orderByAsc(SmartWalletPositionEntity::getLastFilledAt));
        marketInfoService.populatePositions(positions);
        Map<String, List<SmartWalletPositionEntity>> positionsByMarket = new LinkedHashMap<>();
        for (SmartWalletPositionEntity position : positions) {
            if (position == null || !StringUtils.hasText(position.getMarketId())) {
                continue;
            }
            positionsByMarket.computeIfAbsent(position.getMarketId(), ignored -> new ArrayList<>()).add(position);
        }

        int fetched = 0;
        int resolved = 0;
        int unresolved = 0;
        int failed = 0;
        List<String> notes = new ArrayList<>();
        List<SettlementSyncFailure> failures = new ArrayList<>();
        List<String> resolvedMarketIds = new ArrayList<>();

        for (String marketId : marketIds.stream().filter(StringUtils::hasText).distinct().toList()) {
            List<SmartWalletPositionEntity> marketPositions = positionsByMarket.getOrDefault(marketId, List.of());
            if (marketPositions.isEmpty()) {
                unresolved++;
                notes.add("Skip manual confirmation for market " + marketId + " because no active/pending positions were found.");
                continue;
            }
            String question = marketPositions.stream()
                    .map(SmartWalletPositionEntity::getQuestion)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            List<String> outcomeHints = marketPositions.stream()
                    .map(SmartWalletPositionEntity::getOutcome)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .toList();
            try {
                PolymarketApiClient.MarketMetadata metadata = marketMetadataService.findManualSettlementSignal(
                        marketId,
                        refreshCache,
                        question,
                        outcomeHints
                );
                if (metadata == null || !metadata.isResolved()) {
                    unresolved++;
                    notes.add("Manual confirmation skipped for market " + marketId
                            + " [" + abbreviateQuestion(question) + "] because Polymarket did not return a unique final-price signal.");
                    continue;
                }
                // Defense-in-depth：手动确认通道要求市场已经终结。体育/negRisk 市场
                // 可能出现 Gamma closed 标志滞后，但 winningTokenId/UMA resolved 已经明确；
                // 这种场景允许结算，仍然禁止 acceptingOrders=true 的市场提前结算。
                long manualNowMs = System.currentTimeMillis();
                boolean manualMarketEnded = (metadata.getEndAt() != null && metadata.getEndAt() > 0 && metadata.getEndAt() <= manualNowMs)
                        || marketPositions.stream()
                                .map(SmartWalletPositionEntity::getMarketEndAt)
                                .filter(java.util.Objects::nonNull)
                                .anyMatch(ts -> ts > 0 && ts <= manualNowMs);
                SettlementTerminalCheck terminalCheck = settlementTerminalCheck(metadata, manualMarketEnded);
                if (!terminalCheck.canSettle()) {
                    unresolved++;
                    notes.add("Manual confirmation skipped for market " + marketId
                            + " [" + abbreviateQuestion(question) + "] because terminal settlement check failed "
                            + "(endAt=" + metadata.getEndAt()
                            + ", closed=" + metadata.isClosed()
                            + ", acceptingOrders=" + metadata.isAcceptingOrders()
                            + ", reason=" + terminalCheck.reason() + ").");
                    log.warn("Skipping manual confirmation for market {} despite resolvedOutcome={}, winningTokenId={} because marketEnded={}, closed={}, acceptingOrders={}, terminalReason={}, endAt={}",
                            marketId,
                            metadata.getResolvedOutcome(),
                            metadata.getWinningTokenId(),
                            manualMarketEnded,
                            metadata.isClosed(),
                            metadata.isAcceptingOrders(),
                            terminalCheck.reason(),
                            metadata.getEndAt());
                    continue;
                }
                fetched++;
                syncResolvedPaperOrderStatuses(marketId, metadata);
                for (SmartWalletPositionEntity position : marketPositions) {
                    resolvePosition(position, metadata);
                    resolved++;
                }
                resolvedMarketIds.add(marketId);
                notes.add("Manual confirmation resolved market " + marketId
                        + " [" + abbreviateQuestion(question) + "]"
                        + " using source=" + metadata.getResolutionSource()
                        + ", resolvedOutcome=" + metadata.getResolvedOutcome() + ".");
            } catch (RuntimeException e) {
                failed++;
                failures.add(SettlementSyncFailure.builder()
                        .marketId(marketId)
                        .question(question)
                        .errorMessage(e.getMessage())
                        .build());
                notes.add("Manual confirmation failed for market " + marketId + ": " + e.getMessage());
            }
        }
        if (notes.size() > 10) {
            notes = new ArrayList<>(notes.subList(0, 10));
        }
        return SettlementSyncSummary.builder()
                .scannedPositions(positions.size())
                .distinctMarkets((int) marketIds.stream().filter(StringUtils::hasText).distinct().count())
                .fetchedMarkets(fetched)
                .resolvedPositions(resolved)
                .unresolvedMarkets(unresolved)
                .oraclePendingMarkets(unresolved)
                .skippedFutureMarkets(0)
                .failedMarkets(failed)
                .notes(notes)
                .failures(failures)
                .processedMarketIds(resolvedMarketIds)
                .build();
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
        int oraclePending = 0;
        int skippedFuture = 0;
        int failed = 0;
        List<String> notes = new ArrayList<>();
        List<SettlementSyncFailure> failures = new ArrayList<>();
        List<SettlementRetryCandidate> retryCandidates = new ArrayList<>();
        Map<String, PolymarketApiClient.MarketMetadata> metadataByMarket = new LinkedHashMap<>();
        Set<String> unresolvedNotedMarkets = new LinkedHashSet<>();
        Set<String> oraclePendingMarkets = new LinkedHashSet<>();
        Set<String> resolvedOrderStatusSyncedMarkets = new LinkedHashSet<>();
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
            if (metadata.getEndAt() != null
                    && metadata.getEndAt() > System.currentTimeMillis()
                    && !metadata.isClosed()
                    && !settlementTerminalCheck(metadata, false).canSettle()) {
                skippedFuture++;
                continue;
            }
            if (!metadata.isResolved()) {
                boolean marketExpired = (metadata.getEndAt() != null && metadata.getEndAt() <= System.currentTimeMillis())
                        || (position.getMarketEndAt() != null && position.getMarketEndAt() <= System.currentTimeMillis());
                if (metadata.isClosed()) {
                    log.warn("Settlement unresolved after market close: marketId={}, question={}, outcomes={}, outcomePrices={}, acceptingOrders={}",
                            position.getMarketId(),
                            position.getQuestion(),
                            metadata.getOutcomes(),
                            metadata.getOutcomePrices(),
                            metadata.isAcceptingOrders());
                }
                if (marketExpired
                        && StringUtils.hasText(position.getMarketId())
                        && oraclePendingMarkets.add(position.getMarketId())) {
                    oraclePending++;
                    String pendingStatus = settlementPendingStatus(metadata);
                    retryCandidates.add(SettlementRetryCandidate.builder()
                            .marketId(position.getMarketId())
                            .question(questionOrPosition(position, metadata))
                            .retryType(classifySettlementRetryType(metadata))
                            .retryMessage(buildOraclePendingRetryMessage(position, metadata))
                            .resolutionSource(metadata == null ? null : metadata.getResolutionSource())
                            .resolutionObservedAt(metadata == null ? null : metadata.getResolutionObservedAt())
                            .build());
                    markPaperPositionsSettlementStatus(position.getMarketId(), pendingStatus);
                    markPaperOrdersSettlementStatus(position.getMarketId(), orderPendingStatus(pendingStatus));
                }
                if (StringUtils.hasText(position.getMarketId())
                        && unresolvedNotedMarkets.add(position.getMarketId())) {
                    notes.add(marketExpired
                            ? buildOraclePendingSettlementNote(position, metadata)
                            : buildUnresolvedSettlementNote(position, metadata));
                }
                unresolved++;
                continue;
            }
            // Defense-in-depth: 即使 metadata.isResolved()==true，仍要求市场已经终结。
            // 体育/negRisk 市场可能出现 closed=false 滞后；当 winningTokenId/UMA resolved
            // 已经明确且 acceptingOrders=false 时，允许继续结算，避免永久卡在 oracle pending。
            long nowMs = System.currentTimeMillis();
            boolean marketEnded = (metadata.getEndAt() != null && metadata.getEndAt() > 0 && metadata.getEndAt() <= nowMs)
                    || (position.getMarketEndAt() != null && position.getMarketEndAt() > 0 && position.getMarketEndAt() <= nowMs);
            SettlementTerminalCheck terminalCheck = settlementTerminalCheck(metadata, marketEnded);
            if (!terminalCheck.canSettle()) {
                if (StringUtils.hasText(position.getMarketId())
                        && unresolvedNotedMarkets.add(position.getMarketId())) {
                    notes.add("Skipped premature settlement: marketId=" + position.getMarketId()
                            + " (resolvedOutcome=" + metadata.getResolvedOutcome()
                            + ", winningTokenId=" + metadata.getWinningTokenId()
                            + ", endAt=" + metadata.getEndAt()
                            + ", closed=" + metadata.isClosed()
                            + ", acceptingOrders=" + metadata.isAcceptingOrders()
                            + ", reason=" + terminalCheck.reason() + ")");
                }
                log.warn("Skipping settlement for market {} despite resolvedOutcome={}, winningTokenId={} because marketEnded={}, closed={}, acceptingOrders={}, terminalReason={}, endAt={}",
                        position.getMarketId(),
                        metadata.getResolvedOutcome(),
                        metadata.getWinningTokenId(),
                        marketEnded,
                        metadata.isClosed(),
                        metadata.isAcceptingOrders(),
                        terminalCheck.reason(),
                        metadata.getEndAt());
                unresolved++;
                continue;
            }
            if (resolvedOrderStatusSyncedMarkets.add(position.getMarketId())) {
                syncResolvedPaperOrderStatuses(position.getMarketId(), metadata);
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
                .oraclePendingMarkets(oraclePending)
                .skippedFutureMarkets(skippedFuture)
                .failedMarkets(failed)
                .notes(notes)
                .failures(failures)
                .retryCandidates(retryCandidates)
                .processedMarketIds(new ArrayList<>(markets))
                .build();
    }

    private String buildUnresolvedSettlementNote(SmartWalletPositionEntity position,
                                                 PolymarketApiClient.MarketMetadata metadata) {
        String marketId = position == null ? null : position.getMarketId();
        String question = questionOrPosition(position, metadata);
        Long effectiveEndAt = metadata != null && metadata.getEndAt() != null
                ? metadata.getEndAt()
                : (position == null ? null : position.getMarketEndAt());
        String endAtBjt = effectiveEndAt == null ? "unknown" : timeFormatter.formatEpochMillisToBjt(effectiveEndAt);
        boolean closed = metadata != null && metadata.isClosed();
        boolean acceptingOrders = metadata != null && metadata.isAcceptingOrders();
        boolean expired = effectiveEndAt != null && effectiveEndAt <= System.currentTimeMillis();

        if (expired) {
                return "Unresolved expired market " + marketId
                    + " [" + abbreviateQuestion(question) + "]"
                    + ", closed=" + closed
                    + ", acceptingOrders=" + acceptingOrders
                    + ", endAtBjt=" + endAtBjt
                    + oraclePhaseSuffix(metadata)
                    + manualCheckSuffix(metadata) + ".";
        }
        return "Settlement pending for market " + marketId
                + " [" + abbreviateQuestion(question) + "]"
                + ", closed=" + closed
                + ", acceptingOrders=" + acceptingOrders
                + ", endAtBjt=" + endAtBjt
                + oraclePhaseSuffix(metadata)
                + manualCheckSuffix(metadata) + ".";
    }

    private String buildOraclePendingSettlementNote(SmartWalletPositionEntity position,
                                                    PolymarketApiClient.MarketMetadata metadata) {
        String marketId = position == null ? null : position.getMarketId();
        String question = questionOrPosition(position, metadata);
        Long effectiveEndAt = metadata != null && metadata.getEndAt() != null
                ? metadata.getEndAt()
                : (position == null ? null : position.getMarketEndAt());
        String endAtBjt = effectiveEndAt == null ? "unknown" : timeFormatter.formatEpochMillisToBjt(effectiveEndAt);
        boolean closed = metadata != null && metadata.isClosed();
        boolean acceptingOrders = metadata != null && metadata.isAcceptingOrders();
        return "Expired market " + marketId
                + " [" + abbreviateQuestion(question) + "]"
                + " is waiting for Polymarket/UMA oracle resolution"
                + ", closed=" + closed
                + ", acceptingOrders=" + acceptingOrders
                + ", endAtBjt=" + endAtBjt
                + oraclePhaseSuffix(metadata)
                + manualCheckSuffix(metadata) + ".";
    }

    private String buildOraclePendingRetryMessage(SmartWalletPositionEntity position,
                                                  PolymarketApiClient.MarketMetadata metadata) {
        String marketId = position == null ? null : position.getMarketId();
        String question = questionOrPosition(position, metadata);
        if (metadata != null && metadata.needsManualCheck()) {
            return "Market " + marketId
                    + " [" + abbreviateQuestion(question) + "]"
                    + " reached a terminal settlement state but the winner could not be determined safely"
                    + oraclePhaseSuffix(metadata)
                    + manualCheckSuffix(metadata)
                    + "; manual review is required before paper settlement.";
        }
        if (metadata != null && metadata.isWaitingFinalPrice()) {
            return "Market " + marketId
                    + " [" + abbreviateQuestion(question) + "]"
                    + " stopped accepting orders but final prices are not yet a unique 1/0 outcome"
                    + oraclePhaseSuffix(metadata)
                    + manualCheckSuffix(metadata)
                    + "; retry after Gamma final prices stabilize.";
        }
        if (metadata != null && isUmaResolved(metadata) && !metadata.isResolved()) {
            return "Market " + marketId
                    + " [" + abbreviateQuestion(question) + "]"
                    + " has finalized oracle status but the winning outcome/token could not be mapped"
                    + oraclePhaseSuffix(metadata)
                    + "; refresh Gamma token metadata before retrying settlement.";
        }
        if (metadata != null && metadata.isResolved() && !metadata.isClosed() && !metadata.isAcceptingOrders()) {
            return "Market " + marketId
                    + " [" + abbreviateQuestion(question) + "]"
                    + " is resolved but Gamma closed flag is stale"
                    + oraclePhaseSuffix(metadata)
                    + "; retry settlement using resolved token signal.";
        }
        return "Market " + marketId
                + " [" + abbreviateQuestion(question) + "]"
                + " expired and is still waiting for oracle resolution"
                + oraclePhaseSuffix(metadata)
                + "; retry settlement after UMA result is finalized.";
    }

    private String classifySettlementRetryType(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            return "SYNC_FAILURE";
        }
        if (metadata.needsManualCheck()) {
            return "NEED_MANUAL_CHECK";
        }
        if (metadata.isWaitingFinalPrice()) {
            return "WAIT_FINAL_PRICE";
        }
        String umaStatus = StringUtils.hasText(metadata.getUmaResolutionStatus())
                ? metadata.getUmaResolutionStatus().trim().toLowerCase(Locale.ROOT)
                : "";
        long now = System.currentTimeMillis();
        if (isUmaResolved(metadata) && !metadata.isResolved()) {
            return "RESOLVED_OUTCOME_UNMAPPED";
        }
        if (metadata.isResolved() && !metadata.isClosed() && !metadata.isAcceptingOrders()) {
            return "RESOLVED_BUT_CLOSED_FLAG_STALE";
        }
        if (metadata.isResolved() && !StringUtils.hasText(metadata.getSlug())) {
            return "MARKET_INFO_SLUG_OR_MAPPING_ERROR";
        }
        if (metadata.isClosed()) {
            return "DATA_SOURCE_STALE";
        }
        if (StringUtils.hasText(umaStatus)) {
            if (umaStatus.contains("resolved") || umaStatus.contains("settled") || umaStatus.contains("final")) {
                return "DATA_SOURCE_STALE";
            }
            if (umaStatus.contains("proposed")
                    || umaStatus.contains("pending")
                    || umaStatus.contains("dispute")
                    || umaStatus.contains("challenge")
                    || umaStatus.contains("request")) {
                return "ORACLE_PENDING";
            }
        }
        if (metadata.getUmaEndAt() != null && metadata.getUmaEndAt() > 0 && metadata.getUmaEndAt() + 3_600_000L < now) {
            return "DATA_SOURCE_STALE";
        }
        return "ORACLE_PENDING";
    }

    private String settlementPendingStatus(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata != null && metadata.needsManualCheck()) {
            return POSITION_STATUS_NEED_MANUAL_CHECK;
        }
        if (metadata != null && metadata.isWaitingFinalPrice()) {
            return POSITION_STATUS_WAIT_FINAL_PRICE;
        }
        return POSITION_STATUS_SETTLEMENT_PENDING_ORACLE;
    }

    private String orderPendingStatus(String positionPendingStatus) {
        if (POSITION_STATUS_NEED_MANUAL_CHECK.equalsIgnoreCase(positionPendingStatus)) {
            return ORDER_STATUS_NEED_MANUAL_CHECK;
        }
        if (POSITION_STATUS_WAIT_FINAL_PRICE.equalsIgnoreCase(positionPendingStatus)) {
            return ORDER_STATUS_WAIT_FINAL_PRICE;
        }
        return ORDER_STATUS_SETTLEMENT_PENDING_ORACLE;
    }

    private SettlementTerminalCheck settlementTerminalCheck(PolymarketApiClient.MarketMetadata metadata,
                                                            boolean marketEnded) {
        if (metadata == null) {
            return new SettlementTerminalCheck(false, "metadata_null");
        }
        if (!StringUtils.hasText(metadata.getSlug())
                && (metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty())
                && metadata.isClosed()
                && !metadata.isResolved()) {
            return new SettlementTerminalCheck(false, "manual_review_required_missing_slug_and_token");
        }
        if (metadata.isAcceptingOrders()) {
            return new SettlementTerminalCheck(false, "market_still_accepting_orders");
        }
        if (marketEnded && metadata.isClosed()) {
            return new SettlementTerminalCheck(true, "ended_and_closed");
        }
        if (metadata.isResolved() && isUmaResolved(metadata) && !metadata.isAcceptingOrders()) {
            return new SettlementTerminalCheck(true, "uma_resolved_accepting_orders_false");
        }
        if (metadata.isResolved()
                && StringUtils.hasText(metadata.getWinningTokenId())
                && !metadata.isAcceptingOrders()) {
            return new SettlementTerminalCheck(true, "winning_token_accepting_orders_false");
        }
        if (metadata.isResolved()
                && "FINAL_PRICE_INFERENCE".equalsIgnoreCase(metadata.getResolutionSource())
                && !metadata.isAcceptingOrders()) {
            return new SettlementTerminalCheck(true, "final_price_inference_accepting_orders_false");
        }
        if (!marketEnded) {
            return new SettlementTerminalCheck(false, "market_not_ended");
        }
        if (!metadata.isClosed()) {
            return new SettlementTerminalCheck(false, "closed_flag_false_without_authoritative_resolution");
        }
        return new SettlementTerminalCheck(false, "not_terminal");
    }

    private boolean isUmaResolved(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getUmaResolutionStatus())) {
            return false;
        }
        String umaStatus = metadata.getUmaResolutionStatus().trim().toLowerCase(Locale.ROOT);
        return umaStatus.contains("resolved")
                || umaStatus.contains("settled")
                || umaStatus.contains("final");
    }

    private record SettlementTerminalCheck(boolean canSettle, String reason) {
    }

    private String oraclePhaseSuffix(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(metadata.getUmaResolutionStatus())) {
            parts.add("umaResolutionStatus=" + metadata.getUmaResolutionStatus().trim());
        }
        if (StringUtils.hasText(metadata.getResolutionSource())) {
            parts.add("resolutionSource=" + metadata.getResolutionSource().trim());
        }
        if (metadata.getResolutionObservedAt() != null && metadata.getResolutionObservedAt() > 0) {
            parts.add("resolutionObservedAtBjt=" + timeFormatter.formatEpochMillisToBjt(metadata.getResolutionObservedAt()));
        }
        if (StringUtils.hasText(metadata.getQuestionId())) {
            parts.add("questionId=" + metadata.getQuestionId().trim());
        }
        if (metadata.getUmaEndAt() != null && metadata.getUmaEndAt() > 0) {
            parts.add("umaEndAtBjt=" + timeFormatter.formatEpochMillisToBjt(metadata.getUmaEndAt()));
        }
        if (parts.isEmpty()) {
            return "";
        }
        return ", " + String.join(", ", parts);
    }

    private String manualCheckSuffix(PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null || !StringUtils.hasText(metadata.getManualCheckReason())) {
            return "";
        }
        return ", manualCheckReason=" + metadata.getManualCheckReason().trim();
    }

    private void markPaperOrdersAwaitingOracle(String marketId) {
        markPaperOrdersSettlementStatus(marketId, ORDER_STATUS_SETTLEMENT_PENDING_ORACLE);
    }

    private void markPaperOrdersSettlementStatus(String marketId, String nextStatus) {
        if (!StringUtils.hasText(marketId)) {
            return;
        }
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        if (marketInfoIds.isEmpty()) {
            return;
        }
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS)
                .in(SmartWalletOrderEntity::getMarketInfoId, marketInfoIds)
                .in(SmartWalletOrderEntity::getStatus, List.of(
                        "FILLED",
                        "MATCHED",
                        ORDER_STATUS_SETTLEMENT_PENDING_ORACLE,
                        ORDER_STATUS_WAIT_FINAL_PRICE,
                        ORDER_STATUS_NEED_MANUAL_CHECK)));
        Date now = new Date();
        for (SmartWalletOrderEntity order : orders) {
            if (nextStatus.equalsIgnoreCase(order.getStatus())) {
                continue;
            }
            if (isClosedPositionOrder(order)) {
                continue;
            }
            order.setStatus(nextStatus)
                    .setUpdatedAt(now);
            updateOrderChecked(order, "markPaperOrdersSettlementStatus");
        }
    }

    private void syncEntryOrdersAfterPaperExit(SmartWalletPositionEntity position,
                                               SmartWalletOrderEntity exitOrder,
                                               BigDecimal realizedDelta,
                                               Date now) {
        if (position == null || position.getId() == null || exitOrder == null || !isEarlyExitOrder(exitOrder)) {
            return;
        }
        String nextStatus = resolveEarlyExitEntryOrderStatus(exitOrder, realizedDelta);
        List<SmartWalletOrderEntity> entryOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS)
                .eq(SmartWalletOrderEntity::getPositionId, position.getId())
                .ne(SmartWalletOrderEntity::getOrderType, ORDER_TYPE_EXIT)
                .in(SmartWalletOrderEntity::getStatus, List.of(
                        "FILLED",
                        "MATCHED",
                        ORDER_STATUS_SETTLEMENT_PENDING_ORACLE
                )));
        BigDecimal totalEntryNotional = entryOrders.stream()
                .map(SmartWalletOrderEntity::getNotionalUsdc)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Date updateAt = now == null ? new Date() : now;
        for (SmartWalletOrderEntity entryOrder : entryOrders) {
            BigDecimal allocatedPnl = allocateRealizedPnl(realizedDelta, entryOrder, totalEntryNotional);
            if (nextStatus.equalsIgnoreCase(entryOrder.getStatus())
                    && safe(entryOrder.getRealizedPnlUsdc()).compareTo(allocatedPnl) == 0) {
                continue;
            }
            entryOrder.setStatus(nextStatus)
                    .setRealizedPnlUsdc(allocatedPnl)
                    .setUpdatedAt(updateAt);
            updateOrderChecked(entryOrder, "syncEntryOrdersAfterPaperExit");
        }
    }

    private BigDecimal allocateRealizedPnl(BigDecimal realizedDelta,
                                           SmartWalletOrderEntity entryOrder,
                                           BigDecimal totalEntryNotional) {
        BigDecimal pnl = safe(realizedDelta);
        BigDecimal total = safe(totalEntryNotional);
        BigDecimal notional = entryOrder == null ? BigDecimal.ZERO : safe(entryOrder.getNotionalUsdc());
        if (total.compareTo(BigDecimal.ZERO) <= 0 || notional.compareTo(BigDecimal.ZERO) <= 0) {
            return pnl.setScale(8, RoundingMode.HALF_UP);
        }
        return pnl.multiply(notional)
                .divide(total, 8, RoundingMode.HALF_UP)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private String resolveEarlyExitEntryOrderStatus(SmartWalletOrderEntity exitOrder, BigDecimal realizedDelta) {
        String exitReason = resolveOrderExitReason(exitOrder);
        if (TAKE_PROFIT_EXIT_REASON.equalsIgnoreCase(exitReason)) {
            return ORDER_STATUS_SETTLED_WIN;
        }
        if (STOP_LOSS_EXIT_REASON.equalsIgnoreCase(exitReason)) {
            return ORDER_STATUS_SETTLED_LOSS;
        }
        return safe(realizedDelta).compareTo(BigDecimal.ZERO) >= 0
                ? ORDER_STATUS_SETTLED_WIN
                : ORDER_STATUS_SETTLED_LOSS;
    }

    private boolean isClosedPositionOrder(SmartWalletOrderEntity order) {
        if (order == null || order.getPositionId() == null) {
            return false;
        }
        SmartWalletPositionEntity position = positionMapper.selectById(order.getPositionId());
        return position != null && "CLOSED".equalsIgnoreCase(position.getStatus());
    }

    private void markPaperPositionsAwaitingOracle(String marketId) {
        markPaperPositionsSettlementStatus(marketId, POSITION_STATUS_SETTLEMENT_PENDING_ORACLE);
    }

    private void markPaperPositionsSettlementStatus(String marketId, String nextStatus) {
        if (!StringUtils.hasText(marketId)) {
            return;
        }
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        LambdaQueryWrapper<SmartWalletPositionEntity> wrapper = new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS)
                .in(SmartWalletPositionEntity::getStatus, SETTLEMENT_CANDIDATE_POSITION_STATUSES);
        if (marketInfoIds.isEmpty()) {
            wrapper.eq(SmartWalletPositionEntity::getMarketId, marketId);
        } else {
            wrapper.in(SmartWalletPositionEntity::getMarketInfoId, marketInfoIds);
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(wrapper);
        Date now = new Date();
        for (SmartWalletPositionEntity position : positions) {
            if (nextStatus.equalsIgnoreCase(position.getStatus())) {
                continue;
            }
            position.setStatus(nextStatus)
                    .setUpdatedAt(now);
            updatePositionChecked(position, "markPaperPositionsSettlementStatus");
        }
    }

    private void syncResolvedPaperOrderStatuses(String marketId, PolymarketApiClient.MarketMetadata metadata) {
        if (!StringUtils.hasText(marketId) || metadata == null || !metadata.isResolved()) {
            return;
        }
        List<Long> marketInfoIds = marketInfoService.findIdsByMarketId(marketId);
        LambdaQueryWrapper<SmartWalletOrderEntity> wrapper = new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getExecutionAddress, PAPER_BOOK_EXECUTION_ADDRESS)
                .in(SmartWalletOrderEntity::getStatus, EXECUTED_ORDER_STATUSES);
        if (marketInfoIds.isEmpty()) {
            wrapper.eq(SmartWalletOrderEntity::getMarketId, marketId);
        } else {
            wrapper.in(SmartWalletOrderEntity::getMarketInfoId, marketInfoIds);
        }
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(wrapper);
        marketInfoService.populateOrders(orders);
        Date now = new Date();
        for (SmartWalletOrderEntity order : orders) {
            if (isEarlyExitOrder(order)) {
                String earlyExitStatus = resolveEarlyExitOrderStatus(order);
                if (StringUtils.hasText(earlyExitStatus) && !earlyExitStatus.equalsIgnoreCase(order.getStatus())) {
                    order.setStatus(earlyExitStatus);
                    order.setUpdatedAt(now);
                    updateOrderChecked(order, "settleEarlyExitOrder");
                }
                continue;
            }
            Boolean isWinning = marketMetadataService.isWinningPosition(
                    metadata,
                    order.getTokenId(),
                    order.getOutcome());
            if (isWinning == null) {
                log.warn("Skip paper order settlement status sync because winner is indeterminate: orderId={}, marketId={}, outcome={}, tokenId={}, resolvedOutcome={}, winningTokenId={}, resolutionSource={}",
                        order.getId(),
                        marketId,
                        order.getOutcome(),
                        order.getTokenId(),
                        metadata.getResolvedOutcome(),
                        metadata.getWinningTokenId(),
                        metadata.getResolutionSource());
                continue;
            }
            String nextStatus = isWinning ? ORDER_STATUS_SETTLED_WIN : ORDER_STATUS_SETTLED_LOSS;
            if (!nextStatus.equalsIgnoreCase(order.getStatus())) {
                log.info("Sync paper order settlement status: orderId={}, marketId={}, outcome={}, tokenId={}, nextStatus={}, resolvedOutcome={}, winningTokenId={}, resolutionSource={}",
                        order.getId(),
                        marketId,
                        order.getOutcome(),
                        order.getTokenId(),
                        nextStatus,
                        metadata.getResolvedOutcome(),
                        metadata.getWinningTokenId(),
                        metadata.getResolutionSource());
                order.setStatus(nextStatus);
                order.setUpdatedAt(now);
                updateOrderChecked(order, "settleOrderWinLoss");
            }
        }
    }

    private String questionOrPosition(SmartWalletPositionEntity position,
                                      PolymarketApiClient.MarketMetadata metadata) {
        return firstNonBlank(
                position == null ? null : position.getQuestion(),
                metadata == null ? null : metadata.getQuestion(),
                position == null ? null : position.getMarketId()
        );
    }

    private String abbreviateQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            return "n/a";
        }
        String trimmed = question.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 77) + "...";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
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

    private PositionUpdateResult applyFillToPosition(SmartWalletOrderEntity order, SmartWalletFillEntity fill, Long marketEndAt) {
        SmartWalletPositionEntity position = resolvePositionForFill(order, fill, marketEndAt);
        BigDecimal quantity = safe(position.getQuantity());
        BigDecimal costBasis = safe(position.getCostBasisUsdc());
        BigDecimal fillQty = safe(fill.getSize());
        BigDecimal fillNotional = safe(fill.getNotionalUsdc());
        BigDecimal realized = safe(position.getRealizedPnlUsdc());
        BigDecimal realizedDelta = BigDecimal.ZERO;
        BigDecimal previousQuantity = quantity;
        BigDecimal originalCostBasis = safe(position.getOriginalCostBasisUsdc());
        BigDecimal feePolTotal = safe(position.getFeePolTotal());

        if ("SELL".equalsIgnoreCase(fill.getSide())) {
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Cannot apply SELL fill without an active position. marketId="
                        + fill.getMarketId() + ", outcome=" + fill.getOutcome() + ", executionAddress=" + fill.getExecutionAddress());
            }
            BigDecimal averageCost = quantity.compareTo(BigDecimal.ZERO) > 0
                    ? costBasis.divide(quantity, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal reducedQty = fillQty.min(quantity);
            BigDecimal removedCost = averageCost.multiply(reducedQty);
            BigDecimal pnl = fillNotional.subtract(removedCost).subtract(safe(fill.getTradingFeeUsdc(), fill.getFeeUsdc()));
            quantity = quantity.subtract(reducedQty);
            costBasis = costBasis.subtract(removedCost).max(BigDecimal.ZERO);
            realized = realized.add(pnl);
            realizedDelta = pnl;
        } else {
            quantity = quantity.add(fillQty);
            costBasis = costBasis.add(fillNotional);
            originalCostBasis = originalCostBasis.add(fillNotional);
        }
        feePolTotal = feePolTotal.add(safe(fill.getFeePol()));

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

        position.setNetSide(quantity.compareTo(BigDecimal.ZERO) > 0 ? "BUY" : "FLAT")
                .setQuantity(quantity)
                .setAverageEntryPrice(averageEntry)
                .setCostBasisUsdc(costBasis)
                .setOriginalCostBasisUsdc(originalCostBasis)
                .setFeePolTotal(feePolTotal)
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
            if (!updatePositionChecked(position, "upsertPosition")) {
                log.error("upsertPosition: version conflict for position {}, fill will be reconciled on next rebuild",
                        position.getId());
            }
        }
        return new PositionUpdateResult(position, realizedDelta);
    }

    private SmartWalletPositionEntity resolvePositionForFill(SmartWalletOrderEntity order, SmartWalletFillEntity fill, Long marketEndAt) {
        Long linkedPositionId = firstNonNull(fill == null ? null : fill.getPositionId(), order == null ? null : order.getPositionId());
        SmartWalletPositionEntity position = linkedPositionId == null ? null : positionMapper.selectById(linkedPositionId);
        if (position != null) {
            marketInfoService.populate(position);
            return position;
        }
        if ("SELL".equalsIgnoreCase(fill.getSide())) {
            SmartWalletPositionEntity activePosition = findReusableActivePosition(fill);
            if (activePosition != null) {
                return activePosition;
            }
            throw new IllegalStateException("SELL fill does not map to an active position. orderId="
                    + (order == null ? null : order.getId()) + ", marketId=" + fill.getMarketId()
                    + ", outcome=" + fill.getOutcome());
        }
        SmartWalletPositionEntity activePosition = findReusableActivePosition(fill);
        return activePosition != null ? activePosition : newPositionRound(fill, marketEndAt);
    }

    private SmartWalletPositionEntity findReusableActivePosition(SmartWalletFillEntity fill) {
        Long marketInfoId = fill.getMarketInfoId();
        if (marketInfoId == null) return null;
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(SmartWalletPositionEntity::getExecutionAddress, fill.getExecutionAddress())
                .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"))
                .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                .orderByDesc(SmartWalletPositionEntity::getId)
                .last("limit 1"));
        marketInfoService.populatePositions(positions);
        if (positions != null && !positions.isEmpty()) {
            return positions.get(0);
        }
        // Fallback: try matching by marketId + outcome (via market_info)
        if (fill.getMarketInfo() != null && StringUtils.hasText(fill.getMarketInfo().getOutcome())) {
            List<Long> allMarketInfoIds = marketInfoService.findIdsByMarketId(fill.getMarketId());
            if (!allMarketInfoIds.isEmpty()) {
                positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                        .eq(SmartWalletPositionEntity::getExecutionAddress, fill.getExecutionAddress())
                        .in(SmartWalletPositionEntity::getMarketInfoId, allMarketInfoIds)
                        .in(SmartWalletPositionEntity::getStatus, List.of("OPEN", "PARTIALLY_CLOSED"))
                        .orderByDesc(SmartWalletPositionEntity::getLastFilledAt)
                        .orderByDesc(SmartWalletPositionEntity::getId)
                        .last("limit 1"));
            }
        }
        return positions == null || positions.isEmpty() ? null : positions.get(0);
    }

    private SmartWalletPositionEntity newPositionRound(SmartWalletFillEntity fill, Long marketEndAt) {
        return new SmartWalletPositionEntity()
                .setExecutionAddress(fill.getExecutionAddress())
                .setMarketInfoId(fill.getMarketInfoId())
                .setMarketInfo(fill.getMarketInfo())
                .setNetSide(fill.getSide())
                .setQuantity(BigDecimal.ZERO)
                .setAverageEntryPrice(BigDecimal.ZERO)
                .setCostBasisUsdc(BigDecimal.ZERO)
                .setOriginalCostBasisUsdc(BigDecimal.ZERO)
                .setFeePolTotal(BigDecimal.ZERO)
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                .setLastMarkPrice(fill.getPrice())
                .setOpenedAt(fill.getFilledAt())
                .setStatus("OPEN")
                .setCreatedAt(new Date())
                .setUpdatedAt(new Date());
    }

    private void syncOrderAndFillPositionLink(SmartWalletOrderEntity order,
                                              SmartWalletFillEntity fill,
                                              SmartWalletPositionEntity position) {
        if (position == null || position.getId() == null) {
            return;
        }
        Long positionId = position.getId();
        if (!Objects.equals(fill.getPositionId(), positionId)) {
            fill.setPositionId(positionId);
            fillMapper.updateById(fill);
        }
        if (!Objects.equals(order.getPositionId(), positionId)) {
            order.setPositionId(positionId);
            updateOrderChecked(order, "linkPositionToOrder");
        }
    }

    private void syncTradeOrder(SmartWalletPositionEntity position) {
        if (position == null || position.getId() == null) {
            return;
        }
        SmartWalletTradeOrderService service = tradeOrderServiceProvider.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.upsertFromPosition(position.getId());
        } catch (RuntimeException ex) {
            log.warn("Failed to sync smart wallet trade order for positionId={}: {}", position.getId(), ex.getMessage());
        }
    }

    /**
     * 当 position 进入终态（CLOSED / RESOLVED_UNCLAIMED / CLAIMED）时，
     * 同步把关联 follow_task 的 status 更新为 SETTLED，避免前端"open"列表残留。
     */
    private void markFollowTaskSettled(Long positionId) {
        if (positionId == null) {
            return;
        }
        try {
            List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                    .eq(SmartWalletOrderEntity::getPositionId, positionId)
                    .isNotNull(SmartWalletOrderEntity::getFollowTaskId));
            for (SmartWalletOrderEntity order : orders) {
                if (order.getFollowTaskId() == null) continue;
                SmartWalletFollowTaskEntity task = followTaskMapper.selectById(order.getFollowTaskId());
                if (task != null && "FOLLOWED".equalsIgnoreCase(task.getStatus())) {
                    task.setStatus("SETTLED");
                    task.setUpdatedAt(new Date());
                    followTaskMapper.updateById(task);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("[markFollowTaskSettled] positionId={} failed: {}", positionId, ex.getMessage());
        }
    }

    private void appendCashLedger(SmartWalletOrderEntity order, SmartWalletFillEntity fill, PositionUpdateResult positionUpdate) {
        String direction = "SELL".equalsIgnoreCase(fill.getSide()) ? "IN" : "OUT";
        BigDecimal signedAmount = safe(fill.getNotionalUsdc());
        cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                .setExecutionAddress(fill.getExecutionAddress())
                .setAsset(ASSET_USDC_E)
                .setEntryType("ORDER_NOTIONAL")
                .setDirection(direction)
                .setAmount(signedAmount)
                .setReferenceType("ORDER")
                .setReferenceId(order.getId())
                .setMarketId(fill.getMarketId())
                .setQuestion(fill.getQuestion())
                .setOccurredAt(fill.getFilledAt())
                .setCreatedAt(new Date()));
        BigDecimal tradingFeeUsdc = safe(fill.getTradingFeeUsdc(), fill.getFeeUsdc());
        if (tradingFeeUsdc.compareTo(BigDecimal.ZERO) > 0) {
            cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                    .setExecutionAddress(fill.getExecutionAddress())
                    .setAsset(ASSET_USDC_E)
                    .setEntryType("FEE")
                    .setDirection("OUT")
                    .setAmount(tradingFeeUsdc)
                    .setReferenceType("FILL")
                    .setReferenceId(fill.getId())
                    .setMarketId(fill.getMarketId())
                    .setQuestion(fill.getQuestion())
                    .setOccurredAt(fill.getFilledAt())
                    .setCreatedAt(new Date()));
        }
        BigDecimal gasFeeUsdc = safe(fill.getGasFeeUsdc());
        if (gasFeeUsdc.compareTo(BigDecimal.ZERO) > 0) {
            cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                    .setExecutionAddress(fill.getExecutionAddress())
                    .setAsset(ASSET_USDC_E)
                    .setEntryType("NETWORK_FEE")
                    .setDirection("OUT")
                    .setAmount(gasFeeUsdc)
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
                    .setAsset(ASSET_USDC_E)
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
        marketInfoService.populateOrders(orders);
        marketInfoService.populateFills(fills);
        marketInfoService.populatePositions(positions);

        BigDecimal volume = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        for (SmartWalletFillEntity fill : fills) {
            volume = volume.add(safe(fill.getNotionalUsdc()));
        }
        for (SmartWalletCashLedgerEntity entry : ledgerEntries) {
            if ("FEE".equalsIgnoreCase(entry.getEntryType())
                    || "NETWORK_FEE".equalsIgnoreCase(entry.getEntryType())) {
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
        DailyOutcomeBreakdown outcomeBreakdown = deriveDailyOutcomeBreakdown(ledgerEntries);
        int wins = outcomeBreakdown.winningPositions;
        int losses = outcomeBreakdown.losingPositions;
        boolean currentDay = reportDate.equals(LocalDate.now(ZoneId.systemDefault()));
        for (SmartWalletPositionEntity position : positions) {
            if (currentDay && isActivePositionStatus(position.getStatus())) {
                markToMarket(position);
                unrealized = unrealized.add(safe(position.getUnrealizedPnlUsdc()));
            }
        }
        if (!currentDay) {
            unrealized = BigDecimal.ZERO;
        }
        BigDecimal winRate = outcomeBreakdown.winRate();

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
                .setWinningPositions(wins)
                .setLosingPositions(losses)
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
        Date settlementAt = settlementAt(metadata, now);
        String effectiveOutcome = resolveEffectiveOutcome(position.getTokenId(), position.getOutcome(), metadata);
        BigDecimal resolutionPrice = settlementPrice(position, metadata);
        BigDecimal settlementProceeds = safe(position.getQuantity()).multiply(resolutionPrice).setScale(8, RoundingMode.HALF_UP);
        BigDecimal realizedDelta = settlementProceeds.subtract(safe(position.getCostBasisUsdc())).setScale(8, RoundingMode.HALF_UP);
        log.info("Resolve paper position settlement: positionId={}, marketId={}, outcome={}, tokenId={}, quantity={}, costBasisUsdc={}, resolutionPrice={}, settlementProceeds={}, realizedDelta={}, resolvedOutcome={}, winningTokenId={}, resolutionSource={}",
                position.getId(),
                position.getMarketId(),
                position.getOutcome(),
                position.getTokenId(),
                position.getQuantity(),
                position.getCostBasisUsdc(),
                resolutionPrice,
                settlementProceeds,
                realizedDelta,
                metadata.getResolvedOutcome(),
                metadata.getWinningTokenId(),
                metadata.getResolutionSource());

        if (realizedDelta.compareTo(BigDecimal.ZERO) != 0) {
            cashLedgerMapper.insert(new SmartWalletCashLedgerEntity()
                    .setExecutionAddress(position.getExecutionAddress())
                    .setAsset(ASSET_USDC_E)
                    .setEntryType("REALIZED_PNL")
                    .setDirection(realizedDelta.compareTo(BigDecimal.ZERO) >= 0 ? "IN" : "OUT")
                    .setAmount(realizedDelta.abs())
                    .setReferenceType("POSITION")
                    .setReferenceId(position.getId())
                    .setMarketId(position.getMarketId())
                    .setQuestion(position.getQuestion())
                    .setOccurredAt(settlementAt)
                    .setCreatedAt(new Date()));
        }

        final BigDecimal finalResolutionPrice = resolutionPrice;
        final BigDecimal finalRealizedDelta = realizedDelta;
        final Date finalSettlementAt = settlementAt;
        position = updatePositionWithRetry(position.getId(), p -> {
            p.setRealizedPnlUsdc(safe(p.getRealizedPnlUsdc()).add(finalRealizedDelta))
                    .setUnrealizedPnlUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                    .setLastMarkPrice(finalResolutionPrice)
                    .setQuantity(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                    .setOriginalCostBasisUsdc(safe(p.getCostBasisUsdc()).setScale(8, RoundingMode.HALF_UP))
                    .setCostBasisUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                    .setAverageEntryPrice(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                    .setNetSide("FLAT")
                    .setStatus("RESOLVED_UNCLAIMED")
                    .setClosedAt(finalSettlementAt)
                    .setLastFilledAt(finalSettlementAt);
        }, "resolvePosition", 3);
        rebuildDailyReport(position.getExecutionAddress(), toLocalDate(settlementAt));
        syncTradeOrder(position);
        markFollowTaskSettled(position.getId());
    }

    private Date settlementAt(PolymarketApiClient.MarketMetadata metadata, Date fallback) {
        if (metadata != null && metadata.getEndAt() != null && metadata.getEndAt() > 0) {
            return new Date(metadata.getEndAt());
        }
        return fallback == null ? new Date() : fallback;
    }

    private BigDecimal settlementPrice(SmartWalletPositionEntity position, PolymarketApiClient.MarketMetadata metadata) {
        if (metadata == null) {
            // 上游不应该在 metadata=null 时调用本方法，抛错让问题立刻可见
            throw new IllegalStateException("settlementPrice called with null metadata");
        }
        // Path 1 (most reliable): direct winningTokenId comparison
        Boolean isWinning = marketMetadataService.isWinningPosition(
                metadata,
                position == null ? null : position.getTokenId(),
                position == null ? null : position.getOutcome());
        if (isWinning != null) {
            return isWinning
                    ? BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (!StringUtils.hasText(metadata.getResolvedOutcome())) {
            // 允许 winningTokenId-only 的体育/negRisk 结算信号走上面的直接 token 比较；
            // 如果 direct token 也无法判定，则失败式抛错，避免静默按 0 结算。
            throw new IllegalStateException(
                    "settlementPrice called for unresolved market: marketId=" + metadata.getMarketId()
                            + ", conditionId=" + metadata.getConditionId()
                            + ", closed=" + metadata.isClosed()
                            + ", acceptingOrders=" + metadata.isAcceptingOrders()
                            + ", winningTokenId=" + metadata.getWinningTokenId()
                            + ", endAt=" + metadata.getEndAt());
        }
        // Path 2 (fallback): outcome label comparison
        String effectiveOutcome = resolveEffectiveOutcome(position == null ? null : position.getTokenId(),
                position == null ? null : position.getOutcome(),
                metadata);
        return metadata.getResolvedOutcome().equalsIgnoreCase(effectiveOutcome)
                ? BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }

    private String resolveEffectiveOutcome(String tokenId,
                                           String storedOutcome,
                                           PolymarketApiClient.MarketMetadata metadata) {
        return marketMetadataService.resolveOutcomeByTokenId(metadata, tokenId, storedOutcome);
    }

    public void markToMarket(SmartWalletPositionEntity position) {
        marketInfoService.populate(position);
        if (position == null || !StringUtils.hasText(position.getMarketId()) || !StringUtils.hasText(position.getOutcome())) {
            return;
        }
        SmartWalletMarketStateService.MarketStateSnapshot marketState = resolvePositionMarketState(position);
        if (marketState == null) {
            marketState = SmartWalletMarketStateService.MarketStateSnapshot.unknown(position.getMarketId());
        }
        if (!marketState.isActive()) {
            return;
        }
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
            updatePositionChecked(position, "markToMarket");
        }
    }

    private void refreshPositionMarketStates(List<SmartWalletPositionEntity> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        Map<String, SmartWalletMarketStateService.MarketStateSnapshot> statesByMarketId = marketStateService.refreshMarkets(
                positions.stream()
                        .map(SmartWalletPositionEntity::getMarketId)
                        .filter(StringUtils::hasText)
                        .toList(),
                false
        );
        if (statesByMarketId == null) {
            statesByMarketId = Map.of();
        }
        for (SmartWalletPositionEntity position : positions) {
            SmartWalletMarketStateService.MarketStateSnapshot snapshot =
                    statesByMarketId.get(position == null ? null : position.getMarketId());
            if (snapshot == null) {
                continue;
            }
            applySnapshotToPosition(position, snapshot);
            if (SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED.equalsIgnoreCase(snapshot.marketLifecycle())
                    && PAPER_BOOK_EXECUTION_ADDRESS.equalsIgnoreCase(position.getExecutionAddress())
                    && isOpenPositionStatus(position.getStatus())) {
                markPaperPositionsAwaitingOracle(position.getMarketId());
                markPaperOrdersAwaitingOracle(position.getMarketId());
            }
        }
    }

    private SmartWalletMarketStateService.MarketStateSnapshot resolvePositionMarketState(SmartWalletPositionEntity position) {
        marketInfoService.populate(position);
        if (position == null || !StringUtils.hasText(position.getMarketId())) {
            return SmartWalletMarketStateService.MarketStateSnapshot.unknown(null);
        }
        if (StringUtils.hasText(position.getMarketLifecycle())) {
            return SmartWalletMarketStateService.MarketStateSnapshot.builder()
                    .marketId(position.getMarketId())
                    .question(position.getQuestion())
                    .marketEndAt(position.getMarketEndAt())
                    .sourceCategory(position.getSourceCategory())
                    .normalizedCategory(position.getNormalizedCategory())
                    .marketLifecycle(position.getMarketLifecycle())
                    .closed(Boolean.TRUE.equals(position.getClosed()))
                    .acceptingOrders(position.getAcceptingOrders() == null || position.getAcceptingOrders())
                    .resolvedOutcome(position.getResolvedOutcome())
                    .build();
        }
        SmartWalletMarketStateService.MarketStateSnapshot snapshot = marketStateService.refreshMarket(
                position.getMarketId(),
                position.getQuestion(),
                StringUtils.hasText(position.getOutcome()) ? List.of(position.getOutcome()) : List.of()
        );
        if (snapshot == null) {
            snapshot = SmartWalletMarketStateService.MarketStateSnapshot.unknown(position.getMarketId());
        }
        applySnapshotToPosition(position, snapshot);
        return snapshot;
    }

    private void applySnapshotToPosition(SmartWalletPositionEntity position,
                                         SmartWalletMarketStateService.MarketStateSnapshot snapshot) {
        if (position == null || position.getMarketInfo() == null || snapshot == null) {
            return;
        }
        position.getMarketInfo()
                .setQuestion(snapshot.question())
                .setMarketEndAt(snapshot.marketEndAt())
                .setSourceCategory(snapshot.sourceCategory())
                .setNormalizedCategory(snapshot.normalizedCategory())
                .setMarketLifecycle(snapshot.marketLifecycle())
                .setClosed(snapshot.closed())
                .setAcceptingOrders(snapshot.acceptingOrders())
                .setResolvedOutcome(snapshot.resolvedOutcome())
                .setLastMetadataSyncAt(snapshot.lastMetadataSyncAt());
    }

    private boolean isOpenPositionStatus(String status) {
        return "OPEN".equalsIgnoreCase(status)
                || "PARTIALLY_CLOSED".equalsIgnoreCase(status)
                || POSITION_STATUS_SETTLEMENT_PENDING_ORACLE.equalsIgnoreCase(status);
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
        List<SmartWalletFillEntity> fills = loadPositionFills(position);
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
        position.setNetSide(snapshot.quantity().compareTo(BigDecimal.ZERO) > 0 ? "BUY" : "FLAT")
                .setQuantity(snapshot.quantity())
                .setAverageEntryPrice(snapshot.averageEntryPrice())
                .setCostBasisUsdc(snapshot.costBasisUsdc())
                .setOriginalCostBasisUsdc(snapshot.costBasisUsdc())
                .setFeePolTotal(totalFeePolFromFills(fills))
                .setRealizedPnlUsdc(snapshot.realizedPnlUsdc())
                .setUnrealizedPnlUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP))
                .setLastMarkPrice(snapshot.lastPrice())
                .setOpenedAt(snapshot.openedAt())
                .setLastFilledAt(snapshot.lastFilledAt())
                .setClosedAt(snapshot.quantity().compareTo(BigDecimal.ZERO) == 0 ? snapshot.lastFilledAt() : null)
                .setStatus(snapshot.status())
                .setUpdatedAt(new Date());
        if (!updatePositionChecked(position, "repairPrematureSettlement")) {
            notes.add("Version conflict repairing position " + position.getId() + ", will retry next cycle");
            return false;
        }
        // 同步把对应 order 的 SETTLED_WIN/LOSS 误判回滚
        // 仅处理非早退（非 TP/SL）单：早退单的 SETTLED_* 来自真实的 exitReason，应保留
        rollbackPrematureSettledOrders(position, notes);
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

    private void rollbackPrematureSettledOrders(SmartWalletPositionEntity position, List<String> notes) {
        if (position == null || position.getId() == null) {
            return;
        }
        List<SmartWalletOrderEntity> affectedOrders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, position.getId())
                .in(SmartWalletOrderEntity::getStatus, List.of(ORDER_STATUS_SETTLED_WIN, ORDER_STATUS_SETTLED_LOSS)));
        if (affectedOrders.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        boolean marketStillRunning = position.getMarketEndAt() == null
                || position.getMarketEndAt() <= 0
                || position.getMarketEndAt() > nowMs;
        String resetStatus = marketStillRunning
                ? "FILLED"
                : ORDER_STATUS_SETTLEMENT_PENDING_ORACLE;
        Date now = new Date();
        int rolledBack = 0;
        for (SmartWalletOrderEntity order : affectedOrders) {
            // 早退单（TAKE_PROFIT_AUTO_EXIT / STOP_LOSS_AUTO_EXIT）的 SETTLED_* 来自真实的提前止盈止损，应保留
            if (isEarlyExitOrder(order)) {
                continue;
            }
            order.setStatus(resetStatus);
            // 把可能由 prematurely resolvePosition 写入的负值 PnL 抹掉，避免显示层继续把它当成"输"
            order.setRealizedPnlUsdc(BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP));
            order.setUpdatedAt(now);
            if (updateOrderChecked(order, "rollbackPrematureSettledOrder")) {
                rolledBack++;
            }
        }
        if (rolledBack > 0) {
            notes.add("Rolled back " + rolledBack + " prematurely SETTLED_* order(s) for position "
                    + position.getId() + " => " + resetStatus);
        }
    }

    private PositionSnapshot replayPositionFromFills(List<SmartWalletFillEntity> fills) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal lastPrice = BigDecimal.ZERO;
        String tokenId = null;
        Integer indexSet = null;
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
            if (fill.getIndexSet() != null) {
                indexSet = fill.getIndexSet();
            }
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
                BigDecimal pnl = fillNotional.subtract(removedCost).subtract(safe(fill.getTradingFeeUsdc(), fill.getFeeUsdc()));
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
                costBasis = costBasis.add(fillNotional);
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
                indexSet,
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
        if (date instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private BigDecimal calculateMaxDrawdown() {
        return calculateMaxDrawdown(null);
    }

    private BigDecimal calculateMaxDrawdown(String mode) {
        DrawdownCalculation ledgerDrawdown = calculateMaxDrawdownFromLedger(mode);
        if (ledgerDrawdown.hasEvents()) {
            return ledgerDrawdown.maxDrawdown();
        }
        return calculateMaxDrawdownFromDailyReports(mode);
    }

    private DrawdownCalculation calculateMaxDrawdownFromLedger(String mode) {
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .in(SmartWalletCashLedgerEntity::getEntryType, List.of("REALIZED_PNL", "FEE", "NETWORK_FEE"))
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        if (entries == null) {
            entries = List.of();
        }
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        boolean hasEvents = false;
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            BigDecimal pnlDelta = ledgerPnlDelta(entry);
            if (pnlDelta.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            hasEvents = true;
            cumulative = cumulative.add(pnlDelta);
            if (cumulative.compareTo(peak) > 0) {
                peak = cumulative;
            }
            BigDecimal drawdown = peak.subtract(cumulative);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }
        return new DrawdownCalculation(maxDrawdown.setScale(8, RoundingMode.HALF_UP), hasEvents);
    }

    private BigDecimal ledgerPnlDelta(SmartWalletCashLedgerEntity entry) {
        if (entry == null || !StringUtils.hasText(entry.getEntryType())) {
            return BigDecimal.ZERO;
        }
        if ("FEE".equalsIgnoreCase(entry.getEntryType())
                || "NETWORK_FEE".equalsIgnoreCase(entry.getEntryType())) {
            return safe(entry.getAmount()).negate();
        }
        if ("REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
            return "OUT".equalsIgnoreCase(entry.getDirection())
                    ? safe(entry.getAmount()).negate()
                    : safe(entry.getAmount());
        }
        return BigDecimal.ZERO;
    }

    private TimelineBaseline timelineBaselineBefore(Date startDate, String mode) {
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .in(SmartWalletCashLedgerEntity::getEntryType, List.of("REALIZED_PNL", "FEE", "NETWORK_FEE"))
                .lt(SmartWalletCashLedgerEntity::getOccurredAt, startDate)
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        if (entries == null) {
            entries = List.of();
        }
        TimelineBucketAccumulator accumulator = new TimelineBucketAccumulator();
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            BigDecimal delta = ledgerPnlDelta(entry);
            if (delta.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            accumulator.add(entry, delta);
        }
        return new TimelineBaseline(
                accumulator.lossPnl().setScale(8, RoundingMode.HALF_UP),
                accumulator.realizedPnl.setScale(8, RoundingMode.HALF_UP),
                accumulator.fee.setScale(8, RoundingMode.HALF_UP),
                accumulator.netPnl().setScale(8, RoundingMode.HALF_UP)
        );
    }

    private TimelineBucketAccumulator currentDayTimelineBucket(LocalDate today, ZoneId zoneId, String mode) {
        Date start = Date.from(today.atStartOfDay(zoneId).toInstant());
        Date end = Date.from(today.plusDays(1).atStartOfDay(zoneId).toInstant());
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .in(SmartWalletCashLedgerEntity::getEntryType, List.of("REALIZED_PNL", "FEE", "NETWORK_FEE"))
                .ge(SmartWalletCashLedgerEntity::getOccurredAt, start)
                .lt(SmartWalletCashLedgerEntity::getOccurredAt, end)
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        TimelineBucketAccumulator accumulator = new TimelineBucketAccumulator();
        if (entries == null) {
            return accumulator;
        }
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (!matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            BigDecimal delta = ledgerPnlDelta(entry);
            if (delta.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            accumulator.add(entry, delta);
        }
        return accumulator;
    }

    private BigDecimal currentFloatingPnl(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>());
        if (positions == null || positions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        marketInfoService.populatePositions(positions);
        BigDecimal floatingPnl = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            markToMarket(position);
            if (isPendingSettlementTracked(position)) {
                floatingPnl = floatingPnl.add(pendingSettlementNetPnlForPosition(position));
            } else if (isActivePositionStatus(position.getStatus())) {
                floatingPnl = floatingPnl.add(safe(position.getUnrealizedPnlUsdc()));
            }
        }
        return floatingPnl.setScale(8, RoundingMode.HALF_UP);
    }

    private TimelineBaseline dailyTimelineBaseline(List<SmartWalletDailyReportEntity> selectedReports,
                                                   LocalDate today,
                                                   ZoneId zoneId,
                                                   String mode) {
        LocalDate baselineDate = today;
        if (selectedReports != null && !selectedReports.isEmpty()) {
            LocalDate firstReportDate = toLocalDate(selectedReports.get(0).getReportDate());
            if (firstReportDate != null) {
                baselineDate = firstReportDate;
            }
        }
        Date startDate = Date.from(baselineDate.atStartOfDay(zoneId).toInstant());
        return timelineBaselineBefore(startDate, mode);
    }

    private BigDecimal calculateMaxDrawdownFromDailyReports(String mode) {
        List<SmartWalletDailyReportEntity> reports = dailyReportMapper.selectList(new LambdaQueryWrapper<SmartWalletDailyReportEntity>()
                .orderByAsc(SmartWalletDailyReportEntity::getReportDate));
        if (reports == null) {
            reports = List.of();
        }
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
        return maxDrawdown.setScale(8, RoundingMode.HALF_UP);
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

    private boolean isOraclePendingPositionStatus(String status) {
        return POSITION_STATUS_SETTLEMENT_PENDING_ORACLE.equalsIgnoreCase(status);
    }

    private boolean isCapitalLockedPositionStatus(String status) {
        return isActivePositionStatus(status) || isOraclePendingPositionStatus(status);
    }

    private boolean isPendingSettlementTracked(SmartWalletPositionEntity position) {
        if (position == null) {
            return false;
        }
        return isPendingSettlementWindow(position) || "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus());
    }

    private boolean isPendingSettlementWindow(SmartWalletPositionEntity position) {
        if (position == null) {
            return false;
        }
        if (isOraclePendingPositionStatus(position.getStatus())) {
            return true;
        }
        if (!isActivePositionStatus(position.getStatus())) {
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
        PolymarketApiClient.MarketMetadata metadata = safeMarketMetadata(position);
        if (metadata == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal price;
        try {
            price = settlementPrice(position, metadata);
        } catch (RuntimeException ex) {
            log.debug("Skip recomputing settlement proceeds for positionId={} marketId={} because settlement price is unavailable: {}",
                    position == null ? null : position.getId(),
                    position == null ? null : position.getMarketId(),
                    ex.getMessage());
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
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
        return totalBuyCostFromFills(fills);
    }

    private BigDecimal totalBuyCostFromFills(List<SmartWalletFillEntity> fills) {
        BigDecimal total = BigDecimal.ZERO;
        if (fills == null) {
            return total.setScale(8, RoundingMode.HALF_UP);
        }
        for (SmartWalletFillEntity fill : fills) {
            if (!"BUY".equalsIgnoreCase(fill.getSide())) {
                continue;
            }
            total = total.add(safe(fill.getNotionalUsdc()));
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal totalFeePolFromFills(List<SmartWalletFillEntity> fills) {
        BigDecimal total = BigDecimal.ZERO;
        if (fills == null) {
            return total.setScale(8, RoundingMode.HALF_UP);
        }
        for (SmartWalletFillEntity fill : fills) {
            total = total.add(safe(fill.getFeePol()));
        }
        return total.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal pendingSettlementNetPnlForPosition(SmartWalletPositionEntity position) {
        if (position == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (isActivePositionStatus(position.getStatus()) || isOraclePendingPositionStatus(position.getStatus())) {
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
        if (position.getId() != null) {
            List<SmartWalletFillEntity> linkedFills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                    .eq(SmartWalletFillEntity::getPositionId, position.getId())
                    .orderByAsc(SmartWalletFillEntity::getFilledAt)
                    .orderByAsc(SmartWalletFillEntity::getId));
            if (linkedFills != null && !linkedFills.isEmpty()) {
                return linkedFills;
            }
        }
        return fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .isNull(SmartWalletFillEntity::getPositionId)
                .eq(SmartWalletFillEntity::getExecutionAddress, position.getExecutionAddress())
                .eq(SmartWalletFillEntity::getMarketInfoId, position.getMarketInfoId())
                .orderByAsc(SmartWalletFillEntity::getFilledAt)
                .orderByAsc(SmartWalletFillEntity::getId));
    }

    private BigDecimal realizedPnlForPosition(SmartWalletPositionEntity position) {
        if (position == null || position.getId() == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal realized = sumLedgerSignedAmount("REALIZED_PNL", position.getExecutionAddress(), "POSITION", position.getId());
        if (realized.compareTo(BigDecimal.ZERO) == 0 && "RESOLVED_UNCLAIMED".equalsIgnoreCase(position.getStatus())) {
            return lockedClaimProceedsForPosition(position)
                    .subtract(originalCostBasisForPosition(position))
                    .setScale(8, RoundingMode.HALF_UP);
        }
        return realized;
    }

    private BigDecimal safe(BigDecimal primary) {
        return primary == null ? BigDecimal.ZERO : primary;
    }

    private BigDecimal safe(BigDecimal primary, BigDecimal fallback) {
        return primary == null ? safe(fallback) : primary;
    }

    private BigDecimal resolveTradingFeeUsdc(SmartWalletExecutionResult result) {
        if (result == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (result.getTradingFeeUsdc() != null) {
            return safe(result.getTradingFeeUsdc()).setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal totalFee = safe(result.getFeeUsdc());
        BigDecimal explicitGas = safe(result.getGasFeeUsdc());
        if (explicitGas.compareTo(BigDecimal.ZERO) > 0) {
            return totalFee.subtract(explicitGas).max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP);
        }
        return totalFee.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveGasFeeUsdc(SmartWalletExecutionResult result) {
        if (result == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal explicitGas = safe(result.getGasFeeUsdc());
        if (explicitGas.compareTo(BigDecimal.ZERO) > 0) {
            return explicitGas.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal totalFee = safe(result.getFeeUsdc());
        BigDecimal tradingFee = safe(result.getTradingFeeUsdc(), totalFee);
        return totalFee.subtract(tradingFee).max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal totalFeeUsdc(BigDecimal tradingFeeUsdc, BigDecimal gasFeeUsdc) {
        return safe(tradingFeeUsdc).add(safe(gasFeeUsdc)).setScale(8, RoundingMode.HALF_UP);
    }

    private void updateOrderRealizedPnl(SmartWalletOrderEntity order, BigDecimal realizedDelta, Date now) {
        if (order == null || order.getId() == null) {
            return;
        }
        BigDecimal delta = safe(realizedDelta).setScale(8, RoundingMode.HALF_UP);
        if (delta.compareTo(BigDecimal.ZERO) == 0 && order.getRealizedPnlUsdc() != null) {
            return;
        }
        order.setRealizedPnlUsdc(safe(order.getRealizedPnlUsdc()).add(delta).setScale(8, RoundingMode.HALF_UP))
                .setUpdatedAt(now == null ? new Date() : now);
        updateOrderChecked(order, "updateOrderRealizedPnl");
    }

    private ExitOrderSummary summarizeExitOrders(List<SmartWalletOrderEntity> orders,
                                                 List<SmartWalletFillEntity> fills,
                                                 String mode) {
        Map<Long, List<SmartWalletFillEntity>> fillsByPositionId = fills == null
                ? Map.of()
                : fills.stream()
                .filter(fill -> fill.getPositionId() != null)
                .collect(java.util.stream.Collectors.groupingBy(SmartWalletFillEntity::getPositionId));
        int earlyExitOrderCount = 0;
        int takeProfitOrderCount = 0;
        int stopLossOrderCount = 0;
        BigDecimal earlyExitRealizedPnlUsdc = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal takeProfitRealizedPnlUsdc = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal stopLossRealizedPnlUsdc = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        for (SmartWalletOrderEntity order : orders) {
            if (!matchesDashboardMode(order.getExecutionAddress(), mode) || !isEarlyExitOrder(order) || !isCountableExitOrder(order)) {
                continue;
            }
            earlyExitOrderCount++;
            BigDecimal realizedPnlUsdc = resolveOrderRealizedPnl(order, fillsByPositionId);
            earlyExitRealizedPnlUsdc = earlyExitRealizedPnlUsdc.add(realizedPnlUsdc);
            String exitReason = resolveOrderExitReason(order);
            if (TAKE_PROFIT_EXIT_REASON.equalsIgnoreCase(exitReason)) {
                takeProfitOrderCount++;
                takeProfitRealizedPnlUsdc = takeProfitRealizedPnlUsdc.add(realizedPnlUsdc);
            }
            if (STOP_LOSS_EXIT_REASON.equalsIgnoreCase(exitReason)) {
                stopLossOrderCount++;
                stopLossRealizedPnlUsdc = stopLossRealizedPnlUsdc.add(realizedPnlUsdc);
            }
        }
        return new ExitOrderSummary(
                earlyExitOrderCount,
                earlyExitRealizedPnlUsdc.setScale(8, RoundingMode.HALF_UP),
                takeProfitOrderCount,
                takeProfitRealizedPnlUsdc.setScale(8, RoundingMode.HALF_UP),
                stopLossOrderCount,
                stopLossRealizedPnlUsdc.setScale(8, RoundingMode.HALF_UP)
        );
    }

    private boolean isEarlyExitOrder(SmartWalletOrderEntity order) {
        return order != null && ORDER_TYPE_EXIT.equalsIgnoreCase(order.getOrderType());
    }

    private boolean isCountableExitOrder(SmartWalletOrderEntity order) {
        return order != null && !"FAILED".equalsIgnoreCase(order.getStatus());
    }

    private String normalizeExitReason(String reason) {
        return StringUtils.hasText(reason) ? reason.trim() : null;
    }

    private String resolveOrderExitReason(SmartWalletOrderEntity order) {
        if (order == null) {
            return null;
        }
        if (StringUtils.hasText(order.getExitReason())) {
            return order.getExitReason().trim();
        }
        if (!isEarlyExitOrder(order) || !StringUtils.hasText(order.getErrorMessage())) {
            return null;
        }
        String message = order.getErrorMessage();
        int reasonIndex = message.indexOf("reason=");
        if (reasonIndex < 0) {
            return null;
        }
        String tail = message.substring(reasonIndex + "reason=".length()).trim();
        int commaIndex = tail.indexOf(',');
        if (commaIndex >= 0) {
            tail = tail.substring(0, commaIndex).trim();
        }
        return StringUtils.hasText(tail) ? tail : null;
    }

    private BigDecimal resolveOrderRealizedPnl(SmartWalletOrderEntity order,
                                               Map<Long, List<SmartWalletFillEntity>> fillsByPositionId) {
        if (order == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (order.getRealizedPnlUsdc() != null
                && order.getRealizedPnlUsdc().compareTo(BigDecimal.ZERO) != 0) {
            return safe(order.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        }
        if (!"SELL".equalsIgnoreCase(order.getSide())) {
            return resolveSettledEntryOrderRealizedPnl(order, fillsByPositionId);
        }
        if (order.getPositionId() == null) {
            return safe(order.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        }
        List<SmartWalletFillEntity> fills = fillsByPositionId == null
                ? fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getPositionId, order.getPositionId())
                .orderByAsc(SmartWalletFillEntity::getFilledAt)
                .orderByAsc(SmartWalletFillEntity::getId))
                : fillsByPositionId.getOrDefault(order.getPositionId(), List.of());
        if (fills == null || fills.isEmpty()) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realizedForOrder = BigDecimal.ZERO;
        for (SmartWalletFillEntity fill : fills) {
            BigDecimal fillQty = safe(fill.getSize());
            BigDecimal fillNotional = safe(fill.getNotionalUsdc());
            if ("SELL".equalsIgnoreCase(fill.getSide())) {
                BigDecimal averageCost = quantity.compareTo(BigDecimal.ZERO) > 0
                        ? costBasis.divide(quantity, 8, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal reducedQty = fillQty.min(quantity);
                BigDecimal removedCost = averageCost.multiply(reducedQty);
                BigDecimal pnl = fillNotional.subtract(removedCost).subtract(safe(fill.getTradingFeeUsdc(), fill.getFeeUsdc()));
                quantity = quantity.subtract(reducedQty);
                costBasis = costBasis.subtract(removedCost).max(BigDecimal.ZERO);
                if (Objects.equals(fill.getOrderId(), order.getId())) {
                    realizedForOrder = realizedForOrder.add(pnl);
                }
            } else {
                quantity = quantity.add(fillQty);
                costBasis = costBasis.add(fillNotional);
            }
        }
        return realizedForOrder.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveOrderCostBasis(SmartWalletOrderEntity order, BigDecimal realizedPnlUsdc) {
        if (order == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        BigDecimal notional = safe(order.getNotionalUsdc());
        if ("SELL".equalsIgnoreCase(order.getSide())) {
            BigDecimal cost = notional.subtract(safe(realizedPnlUsdc))
                    .subtract(safe(order.getTradingFeeUsdc(), order.getFeeUsdc()));
            return cost.max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP);
        }
        return notional.setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveSettledEntryOrderRealizedPnl(SmartWalletOrderEntity order,
                                                           Map<Long, List<SmartWalletFillEntity>> fillsByPositionId) {
        if (order == null || order.getPositionId() == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        List<SmartWalletFillEntity> fills = fillsByPositionId == null
                ? fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getPositionId, order.getPositionId())
                .orderByAsc(SmartWalletFillEntity::getFilledAt)
                .orderByAsc(SmartWalletFillEntity::getId))
                : fillsByPositionId.getOrDefault(order.getPositionId(), List.of());
        if (fills == null || fills.isEmpty()) {
            SmartWalletPositionEntity position = positionMapper.selectById(order.getPositionId());
            if (position == null || !isPositionClosedForRealizedPnl(position)) {
                return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
            }
            return safe(position.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        }

        SmartWalletPositionEntity position = positionMapper.selectById(order.getPositionId());
        if (position == null) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        if (!isEntryOrderRealized(order, position, fills)) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }

        BigDecimal totalEntryCost = BigDecimal.ZERO;
        BigDecimal orderEntryCost = BigDecimal.ZERO;
        for (SmartWalletFillEntity fill : fills) {
            if (!"BUY".equalsIgnoreCase(fill.getSide())) {
                continue;
            }
            BigDecimal entryCost = safe(fill.getNotionalUsdc());
            totalEntryCost = totalEntryCost.add(entryCost);
            if (Objects.equals(fill.getOrderId(), order.getId())) {
                orderEntryCost = orderEntryCost.add(entryCost);
            }
        }
        if (totalEntryCost.compareTo(BigDecimal.ZERO) <= 0 || orderEntryCost.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }

        return safe(position.getRealizedPnlUsdc())
                .multiply(orderEntryCost)
                .divide(totalEntryCost, 8, RoundingMode.HALF_UP)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private boolean isEntryOrderRealized(SmartWalletOrderEntity order,
                                         SmartWalletPositionEntity position,
                                         List<SmartWalletFillEntity> fills) {
        if (ORDER_STATUS_SETTLED_WIN.equalsIgnoreCase(order.getStatus())
                || ORDER_STATUS_SETTLED_LOSS.equalsIgnoreCase(order.getStatus())) {
            return true;
        }
        boolean hasSellFill = fills != null && fills.stream().anyMatch(fill -> "SELL".equalsIgnoreCase(fill.getSide()));
        return hasSellFill && isPositionClosedForRealizedPnl(position);
    }

    private boolean isPositionClosedForRealizedPnl(SmartWalletPositionEntity position) {
        if (position == null) {
            return false;
        }
        String status = position.getStatus();
        return "CLOSED".equalsIgnoreCase(status)
                || "RESOLVED_UNCLAIMED".equalsIgnoreCase(status)
                || "CLAIMED".equalsIgnoreCase(status);
    }

    private int countOrdersByMode(String mode) {
        return orderMapper.countByMode(normalizeDashboardMode(mode));
    }

    private BigDecimal availableCapitalUsdcByMode(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        marketInfoService.populatePositions(positions);
        BigDecimal realized = sumLedgerSignedAmountByMode("REALIZED_PNL", mode, null, null);
        BigDecimal fees = totalFeeAmountByMode(mode, null, null);
        BigDecimal openCost = BigDecimal.ZERO;
        BigDecimal lockedClaimProceeds = BigDecimal.ZERO;
        for (SmartWalletPositionEntity position : positions) {
            if (!matchesDashboardMode(position.getExecutionAddress(), mode)) {
                continue;
            }
            if (isCapitalLockedPositionStatus(position.getStatus())) {
                openCost = openCost.add(safe(position.getCostBasisUsdc()));
            }
            lockedClaimProceeds = lockedClaimProceeds.add(lockedClaimProceedsForPosition(position));
        }
        BigDecimal budget = safe(properties.getSignalRisk().getMaxTotalOpenCostUsdc());
        return budget.add(realized).subtract(fees).subtract(openCost).subtract(lockedClaimProceeds).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal lockedClaimProceedsUsdcByMode(String mode) {
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<>());
        marketInfoService.populatePositions(positions);
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

    private String normalizeFilterValue(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
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
        return safe(cashLedgerMapper.sumAmountByMode(entryType, normalizeDashboardMode(mode), referenceType, referenceId))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal totalFeeAmountByMode(String mode, String referenceType, Long referenceId) {
        return sumLedgerAmountByMode("FEE", mode, referenceType, referenceId)
                .add(sumLedgerAmountByMode("NETWORK_FEE", mode, referenceType, referenceId))
                .setScale(8, RoundingMode.HALF_UP);
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
        return safe(cashLedgerMapper.sumSignedAmountByMode(entryType, normalizeDashboardMode(mode), referenceType, referenceId))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private SmartWalletOrderView toOrderView(SmartWalletOrderEntity order) {
        Long marketEndAt = resolveStoredOrderMarketEndAt(order);
        SignalRiskDecision decision = resolveOrderRiskDecision(order);
        SmartWalletOrderEntity pairedExitOrder = resolvePairedExitOrder(order);
        String orderExitReason = resolveOrderExitReason(order);
        String pairedExitReason = resolveOrderExitReason(pairedExitOrder);
        String exitReason = StringUtils.hasText(orderExitReason) ? orderExitReason : pairedExitReason;
        boolean earlyExit = isEarlyExitOrder(order) || pairedExitOrder != null;
        BigDecimal realizedPnlUsdc = resolveOrderRealizedPnl(order, null);
        BigDecimal costBasisUsdc = resolveOrderCostBasis(order, realizedPnlUsdc);
        String displayStatus = resolveDisplayOrderStatus(order, realizedPnlUsdc, pairedExitOrder);
        String orderUnitMismatch = orderUnitMismatchReason(order);
        String marketLifecycle = resolveOrderMarketLifecycle(order, marketEndAt);
        boolean activeMarket = isActiveOrderMarket(marketLifecycle, order, marketEndAt);
        SmartWalletMarketLinkView marketLink = resolveOrderMarketLink(order);
        SmartWalletTradeEventEntity sourceEvent = resolveOrderSourceEvent(order);
        String abnormalMarketReason = AbnormalMarketReviewService.abnormalMarketReason(
                order.getTokenId(),
                order.getMarketSlug(),
                marketLifecycle,
                order.getClosed(),
                order.getAcceptingOrders());
        if (StringUtils.hasText(orderUnitMismatch)) {
            log.warn("[ORDER_UNIT_MISMATCH] orderId={} marketId={} outcome={} price={} size={} notionalUsdc={} reason={}",
                    order.getId(), order.getMarketId(), order.getOutcome(), order.getPrice(), order.getSize(),
                    order.getNotionalUsdc(), orderUnitMismatch);
        }
        return SmartWalletOrderView.builder()
                .id(order.getId())
                .followTaskId(order.getFollowTaskId())
                .walletAddress(order.getWalletAddress())
                .executionMode(order.getExecutionMode())
                .executionAddress(order.getExecutionAddress())
                .externalOrderId(order.getExternalOrderId())
                .sourceEventId(sourceEvent == null ? null : sourceEvent.getId())
                .externalTradeId(sourceEvent == null ? null : sourceEvent.getExternalTradeId())
                .marketId(order.getMarketId())
                .marketSlug(order.getMarketSlug())
                .marketUrl(marketLink == null ? null : marketLink.getMarketUrl())
                .externalMarketUrl(marketLink == null ? null : marketLink.getExternalMarketUrl())
                .internalMarketUrl(marketLink == null ? null : marketLink.getInternalMarketUrl())
                .marketLinkStatus(marketLink == null ? null : marketLink.getLinkStatus())
                .tokenId(order.getTokenId())
                .question(order.getQuestion())
                .outcome(order.getOutcome())
                .marketEndAt(marketEndAt)
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(marketEndAt))
                .marketLifecycle(marketLifecycle)
                .activeMarket(activeMarket)
                .closed(Boolean.TRUE.equals(order.getClosed()))
                .acceptingOrders(order.getAcceptingOrders() == null || order.getAcceptingOrders())
                .metadataMissing(StringUtils.hasText(abnormalMarketReason))
                .manualReviewRequired("MANUAL_REVIEW_REQUIRED".equals(abnormalMarketReason))
                .abnormalMarketReason(abnormalMarketReason)
                .normalizedCategory(order.getNormalizedCategory())
                .sourceCategory(order.getSourceCategory())
                .side(order.getSide())
                .orderType(order.getOrderType())
                .earlyExit(earlyExit)
                .exitReason(exitReason)
                .status(displayStatus)
                .price(order.getPrice())
                .size(order.getSize())
                .notionalUsdc(order.getNotionalUsdc())
                .entryQuantity(order.getSize())
                .entryAvgPrice(order.getPrice())
                .entryCostUsdc(order.getNotionalUsdc())
                .roiBaseUsdc(costBasisUsdc)
                .roiAnomalyReason(orderUnitMismatch)
                .costBasisUsdc(costBasisUsdc)
                .feeUsdc(order.getFeeUsdc())
                .tradingFeeUsdc(order.getTradingFeeUsdc())
                .feePol(order.getFeePol())
                .gasFeeUsdc(order.getGasFeeUsdc())
                .realizedPnlUsdc(realizedPnlUsdc)
                .estimatedFeeUsdc(decision == null ? null : decision.getEstimatedFeeUsdc())
                .estimatedSlippageUsdc(decision == null ? null : decision.getEstimatedSlippageUsdc())
                .followProbability(decision == null ? null : decision.getFollowProbability())
                .calibratedFollowProbability(decision == null ? null : decision.getCalibratedFollowProbability())
                .slippageSpikeProbability(decision == null ? null : decision.getSlippageSpikeProbability())
                .estimateSampleSize(decision == null ? null : decision.getEstimateSampleSize())
                .errorMessage(order.getErrorMessage())
                .placedAt(order.getPlacedAt())
                .filledAt(order.getFilledAt())
                .build();
    }

    private SmartWalletTradeEventEntity resolveOrderSourceEvent(SmartWalletOrderEntity order) {
        if (order == null || order.getFollowTaskId() == null) {
            return null;
        }
        SmartWalletFollowTaskEntity task = followTaskMapper.selectById(order.getFollowTaskId());
        if (task == null || task.getSourceEventId() == null) {
            return null;
        }
        return tradeEventMapper.selectById(task.getSourceEventId());
    }

    private SmartWalletMarketLinkView resolveOrderMarketLink(SmartWalletOrderEntity order) {
        if (order == null || !StringUtils.hasText(order.getMarketId())) {
            return null;
        }
        if (StringUtils.hasText(order.getMarketSlug())) {
            return marketLinkService.fromStoredSlug(
                    order.getMarketId(),
                    order.getQuestion(),
                    order.getOutcome(),
                    order.getMarketSlug());
        }
        return marketLinkService.resolve(order.getMarketId(), order.getQuestion(), order.getOutcome());
    }

    private String resolveOrderMarketLifecycle(SmartWalletOrderEntity order, Long marketEndAt) {
        String lifecycle = order == null ? null : order.getMarketLifecycle();
        if (StringUtils.hasText(order == null ? null : order.getResolvedOutcome())) {
            return SmartWalletMarketStateService.LIFECYCLE_RESOLVED;
        }
        if (Boolean.TRUE.equals(order == null ? null : order.getClosed())) {
            return SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED;
        }
        if (order != null && order.getAcceptingOrders() != null && !order.getAcceptingOrders()) {
            return SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED;
        }
        return StringUtils.hasText(lifecycle) ? lifecycle : SmartWalletMarketStateService.LIFECYCLE_ACTIVE;
    }

    private boolean isActiveOrderMarket(String marketLifecycle, SmartWalletOrderEntity order, Long marketEndAt) {
        if (!SmartWalletMarketStateService.LIFECYCLE_ACTIVE.equalsIgnoreCase(marketLifecycle)) {
            return false;
        }
        if (Boolean.TRUE.equals(order == null ? null : order.getClosed())) {
            return false;
        }
        if (order != null && order.getAcceptingOrders() != null && !order.getAcceptingOrders()) {
            return false;
        }
        return true;
    }

    private String resolveDisplayOrderStatus(SmartWalletOrderEntity order, BigDecimal realizedPnlUsdc) {
        return resolveDisplayOrderStatus(order, realizedPnlUsdc, null);
    }

    private String resolveDisplayOrderStatus(SmartWalletOrderEntity order,
                                             BigDecimal realizedPnlUsdc,
                                             SmartWalletOrderEntity pairedExitOrder) {
        if (order == null || !isEarlyExitOrder(order)) {
            if (order == null) {
                return null;
            }
            if (pairedExitOrder != null) {
                String earlyExitStatus = resolveEarlyExitEntryOrderStatus(pairedExitOrder, realizedPnlUsdc);
                if (StringUtils.hasText(earlyExitStatus)) {
                    return earlyExitStatus;
                }
            }
            return order.getStatus();
        }
        String earlyExitStatus = resolveEarlyExitOrderStatus(order);
        if (StringUtils.hasText(earlyExitStatus)) {
            return earlyExitStatus;
        }
        return order.getStatus();
    }

    private String resolveEarlyExitOrderStatus(SmartWalletOrderEntity order) {
        String exitReason = resolveOrderExitReason(order);
        if (TAKE_PROFIT_EXIT_REASON.equalsIgnoreCase(exitReason)) {
            return ORDER_STATUS_SETTLED_WIN;
        }
        if (STOP_LOSS_EXIT_REASON.equalsIgnoreCase(exitReason)) {
            return ORDER_STATUS_SETTLED_LOSS;
        }
        return "FILLED";
    }

    private SmartWalletOrderEntity resolvePairedExitOrder(SmartWalletOrderEntity order) {
        if (order == null || order.getPositionId() == null || isEarlyExitOrder(order)) {
            return null;
        }
        return orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                        .eq(SmartWalletOrderEntity::getPositionId, order.getPositionId())
                        .eq(SmartWalletOrderEntity::getOrderType, ORDER_TYPE_EXIT)
                        .ne(SmartWalletOrderEntity::getStatus, "FAILED")
                        .orderByDesc(SmartWalletOrderEntity::getFilledAt)
                        .orderByDesc(SmartWalletOrderEntity::getId)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
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
            log.debug("Failed to resolve order risk metrics for orderId={} followTaskId={}: {}",
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
                .tradingFeeUsdc(fill.getTradingFeeUsdc())
                .feePol(fill.getFeePol())
                .gasFeeUsdc(fill.getGasFeeUsdc())
                .realizedPnlUsdc(fill.getRealizedPnlUsdc())
                .filledAt(fill.getFilledAt())
                .build();
    }

    private SmartWalletPositionView toPositionView(SmartWalletPositionEntity position) {
        Long marketEndAt = resolveStoredPositionMarketEndAt(position);
        PolymarketApiClient.MarketMetadata metadata = safeMarketMetadata(position);
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
                .oracleQuestionId(metadata == null ? null : metadata.getQuestionId())
                .oracleResolutionStatus(metadata == null ? null : metadata.getUmaResolutionStatus())
                .oracleEndAt(metadata == null ? null : metadata.getUmaEndAt())
                .oracleEndAtBjt(timeFormatter.formatEpochMillisToBjt(metadata == null ? null : metadata.getUmaEndAt()))
                .question(position.getQuestion())
                .outcome(position.getOutcome())
                .marketEndAt(marketEndAt)
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(marketEndAt))
                .netSide(position.getNetSide())
                .quantity(position.getQuantity())
                .averageEntryPrice(position.getAverageEntryPrice())
                .costBasisUsdc(position.getCostBasisUsdc())
                .originalCostBasisUsdc(originalCostBasis)
                .feePolTotal(position.getFeePolTotal())
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

    private SmartWalletTradeView toTradeView(SmartWalletPositionEntity position) {
        marketInfoService.populate(position);
        List<SmartWalletOrderEntity> orders = position == null || position.getId() == null
                ? List.of()
                : orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .eq(SmartWalletOrderEntity::getPositionId, position.getId())
                .orderByAsc(SmartWalletOrderEntity::getPlacedAt)
                .orderByAsc(SmartWalletOrderEntity::getId));
        List<SmartWalletOrderEntity> entryOrders = orders.stream()
                .filter(order -> order != null && !ORDER_TYPE_EXIT.equalsIgnoreCase(order.getOrderType()))
                .toList();
        List<SmartWalletOrderEntity> exitOrders = orders.stream()
                .filter(this::isEarlyExitOrder)
                .filter(this::isCountableExitOrder)
                .toList();
        BigDecimal entryQuantity = entryOrders.stream()
                .map(SmartWalletOrderEntity::getSize)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal entryCost = entryOrders.stream()
                .map(SmartWalletOrderEntity::getNotionalUsdc)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal exitQuantity = exitOrders.stream()
                .map(SmartWalletOrderEntity::getSize)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal exitProceeds = exitOrders.stream()
                .map(SmartWalletOrderEntity::getNotionalUsdc)
                .map(this::safe)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal realized = safe(position == null ? null : position.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal unrealized = safe(position == null ? null : position.getUnrealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
        BigDecimal totalPnl = realized.add(unrealized).setScale(8, RoundingMode.HALF_UP);
        BigDecimal roiBase = entryCost.compareTo(BigDecimal.ZERO) > 0
                ? entryCost
                : safe(position == null ? null : position.getOriginalCostBasisUsdc());
        BigDecimal roiRate = roiBase.compareTo(BigDecimal.ZERO) > 0
                ? totalPnl.divide(roiBase, 8, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        String roiAnomalyReason = tradeRoiAnomalyReason(entryQuantity, entryCost, totalPnl, roiRate, position);
        if (StringUtils.hasText(roiAnomalyReason)) {
            log.warn("[ORDER_UNIT_MISMATCH] positionId={} marketId={} outcome={} entryQuantity={} entryCostUsdc={} roiRate={} reason={}",
                    position == null ? null : position.getId(),
                    position == null ? null : position.getMarketId(),
                    position == null ? null : position.getOutcome(),
                    entryQuantity, entryCost, roiRate, roiAnomalyReason);
        }
        Long marketEndAt = resolveStoredPositionMarketEndAt(position);
        return SmartWalletTradeView.builder()
                .tradeId(position == null ? null : position.getId())
                .executionAddress(position == null ? null : position.getExecutionAddress())
                .marketId(position == null ? null : position.getMarketId())
                .marketSlug(position == null || position.getMarketInfo() == null ? null : position.getMarketInfo().getMarketSlug())
                .tokenId(position == null ? null : position.getTokenId())
                .question(position == null ? null : position.getQuestion())
                .outcome(position == null ? null : position.getOutcome())
                .status(position == null ? null : position.getStatus())
                .netSide(position == null ? null : position.getNetSide())
                .entryQuantity(entryQuantity)
                .entryAvgPrice(weightedAverage(entryCost, entryQuantity))
                .entryCostUsdc(entryCost)
                .exitQuantity(exitQuantity)
                .exitAvgPrice(weightedAverage(exitProceeds, exitQuantity))
                .exitProceedsUsdc(exitProceeds)
                .remainingQuantity(safe(position == null ? null : position.getQuantity()).setScale(8, RoundingMode.HALF_UP))
                .realizedPnlUsdc(realized)
                .unrealizedPnlUsdc(unrealized)
                .totalPnlUsdc(totalPnl)
                .roiRate(roiRate)
                .roiBaseUsdc(roiBase.setScale(8, RoundingMode.HALF_UP))
                .roiAnomalyReason(roiAnomalyReason)
                .lastMarkPrice(safe(position == null ? null : position.getLastMarkPrice()).setScale(8, RoundingMode.HALF_UP))
                .exitReason(resolveTradeExitReason(exitOrders))
                .entryOrderCount(entryOrders.size())
                .exitOrderCount(exitOrders.size())
                .entryOrderIds(entryOrders.stream().map(SmartWalletOrderEntity::getId).filter(Objects::nonNull).toList())
                .exitOrderIds(exitOrders.stream().map(SmartWalletOrderEntity::getId).filter(Objects::nonNull).toList())
                .marketEndAt(marketEndAt)
                .marketEndAtBjt(timeFormatter.formatEpochMillisToBjt(marketEndAt))
                .openedAt(position == null ? null : position.getOpenedAt())
                .lastFilledAt(position == null ? null : position.getLastFilledAt())
                .closedAt(position == null ? null : position.getClosedAt())
                .build();
    }

    private BigDecimal weightedAverage(BigDecimal notional, BigDecimal quantity) {
        BigDecimal safeQuantity = safe(quantity);
        if (safeQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return safe(notional).divide(safeQuantity, 8, RoundingMode.HALF_UP).setScale(8, RoundingMode.HALF_UP);
    }

    private String orderUnitMismatchReason(SmartWalletOrderEntity order) {
        if (order == null || safe(order.getPrice()).compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal expectedNotional = safe(order.getPrice()).multiply(safe(order.getSize())).setScale(8, RoundingMode.HALF_UP);
        BigDecimal diff = safe(order.getNotionalUsdc()).subtract(expectedNotional).abs();
        if (diff.compareTo(new BigDecimal("0.01")) > 0) {
            return "notional_usdc != price * size; expected=" + expectedNotional + ", diff=" + diff;
        }
        return null;
    }

    private String tradeRoiAnomalyReason(BigDecimal entryQuantity,
                                         BigDecimal entryCost,
                                         BigDecimal totalPnl,
                                         BigDecimal roiRate,
                                         SmartWalletPositionEntity position) {
        if (safe(entryQuantity).compareTo(BigDecimal.ZERO) <= 0 || safe(entryCost).compareTo(BigDecimal.ZERO) <= 0) {
            return "missing entry quantity/cost for ROI base";
        }
        BigDecimal entryAvgPrice = weightedAverage(entryCost, entryQuantity);
        if (entryAvgPrice.compareTo(BigDecimal.ZERO) <= 0 || entryAvgPrice.compareTo(BigDecimal.ONE) >= 0) {
            return null;
        }
        BigDecimal theoreticalMaxWinRoi = BigDecimal.ONE.subtract(entryAvgPrice)
                .divide(entryAvgPrice, 8, RoundingMode.HALF_UP);
        if (safe(roiRate).compareTo(theoreticalMaxWinRoi.add(new BigDecimal("0.05"))) > 0) {
            return "roi exceeds binary payout ceiling; theoreticalMax=" + theoreticalMaxWinRoi
                    + ", totalPnl=" + safe(totalPnl)
                    + ", entryAvgPrice=" + entryAvgPrice
                    + ", status=" + (position == null ? null : position.getStatus());
        }
        return null;
    }

    private String resolveTradeExitReason(List<SmartWalletOrderEntity> exitOrders) {
        if (exitOrders == null || exitOrders.isEmpty()) {
            return null;
        }
        for (int index = exitOrders.size() - 1; index >= 0; index--) {
            String reason = resolveOrderExitReason(exitOrders.get(index));
            if (StringUtils.hasText(reason)) {
                return reason;
            }
        }
        return null;
    }

    private boolean matchesTradeFilters(SmartWalletTradeView view, String pnl, String q) {
        return matchesTradeFilters(view, pnl, null, q);
    }

    private boolean matchesTradeFilters(SmartWalletTradeView view, String pnl, String settlement, String q) {
        if (view == null) {
            return false;
        }
        String normalizedPnl = StringUtils.hasText(pnl) ? pnl.trim().toUpperCase(Locale.ROOT) : "ALL";
        BigDecimal totalPnl = safe(view.getTotalPnlUsdc());
        if ("PROFIT".equals(normalizedPnl) && totalPnl.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if ("LOSS".equals(normalizedPnl) && totalPnl.compareTo(BigDecimal.ZERO) >= 0) {
            return false;
        }
        if ("ZERO".equals(normalizedPnl) && totalPnl.compareTo(BigDecimal.ZERO) != 0) {
            return false;
        }
        String normalizedSettlement = normalizeFilterValue(settlement);
        if ("SETTLED".equals(normalizedSettlement) && !isTerminalTradeStatus(view.getStatus())) {
            return false;
        }
        if ("UNSETTLED".equals(normalizedSettlement) && isTerminalTradeStatus(view.getStatus())) {
            return false;
        }
        if ("EARLY_EXIT".equals(normalizedSettlement) && !StringUtils.hasText(view.getExitReason())) {
            return false;
        }
        if (!StringUtils.hasText(q)) {
            return true;
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(view.getQuestion(), needle)
                || containsIgnoreCase(view.getMarketId(), needle)
                || containsIgnoreCase(view.getOutcome(), needle)
                || containsIgnoreCase(view.getStatus(), needle)
                || containsIgnoreCase(view.getExecutionAddress(), needle);
    }

    private void applyTradeSettlementSqlFilter(LambdaQueryWrapper<SmartWalletPositionEntity> wrapper, String settlement) {
        String normalizedSettlement = normalizeFilterValue(settlement);
        if ("SETTLED".equals(normalizedSettlement)) {
            wrapper.in(SmartWalletPositionEntity::getStatus, terminalTradeStatuses());
        } else if ("UNSETTLED".equals(normalizedSettlement)) {
            wrapper.notIn(SmartWalletPositionEntity::getStatus, terminalTradeStatuses());
        } else if ("EARLY_EXIT".equals(normalizedSettlement)) {
            wrapper.eq(SmartWalletPositionEntity::getStatus, "CLOSED");
        }
    }

    private List<String> terminalTradeStatuses() {
        return List.of("CLOSED", "RESOLVED_UNCLAIMED", "CLAIMED");
    }

    private boolean isTerminalTradeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        return terminalTradeStatuses().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(status.trim()));
    }

    private boolean containsIgnoreCase(String value, String normalizedNeedle) {
        return StringUtils.hasText(value)
                && StringUtils.hasText(normalizedNeedle)
                && value.toLowerCase(Locale.ROOT).contains(normalizedNeedle);
    }

    private PolymarketApiClient.MarketMetadata safeMarketMetadata(SmartWalletPositionEntity position) {
        if (position == null || !StringUtils.hasText(position.getMarketId())) {
            return null;
        }
        try {
            return marketMetadataService.getByConditionId(position.getMarketId(), false, position.getQuestion());
        } catch (Exception ex) {
            log.debug("Failed to load market metadata for positionId={} marketId={}: {}",
                    position.getId(), position.getMarketId(), ex.getMessage());
            return null;
        }
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
        if (marketEndAt != null && marketEndAt > 0 && task.getMarketInfoId() != null) {
            SmartWalletMarketInfoEntity mi = marketInfoService.getById(task.getMarketInfoId());
            if (mi != null && (mi.getMarketEndAt() == null || mi.getMarketEndAt() <= 0)) {
                mi.setMarketEndAt(marketEndAt);
                // The getOrCreate will handle update
                marketInfoService.getOrCreate(mi.getMarketId(), mi.getOutcome(), mi.getQuestion(), mi.getTokenId(), mi.getIndexSet(), marketEndAt);
            }
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
        if (marketEndAt != null && marketEndAt > 0 && order.getMarketInfoId() != null) {
            SmartWalletMarketInfoEntity mi = marketInfoService.getById(order.getMarketInfoId());
            if (mi != null && (mi.getMarketEndAt() == null || mi.getMarketEndAt() <= 0)) {
                mi.setMarketEndAt(marketEndAt);
                marketInfoService.getOrCreate(mi.getMarketId(), mi.getOutcome(), mi.getQuestion(), mi.getTokenId(), mi.getIndexSet(), marketEndAt);
            }
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
        if (marketEndAt != null && marketEndAt > 0 && position.getMarketInfoId() != null) {
            SmartWalletMarketInfoEntity mi = marketInfoService.getById(position.getMarketInfoId());
            if (mi != null && (mi.getMarketEndAt() == null || mi.getMarketEndAt() <= 0)) {
                mi.setMarketEndAt(marketEndAt);
                marketInfoService.getOrCreate(mi.getMarketId(), mi.getOutcome(), mi.getQuestion(), mi.getTokenId(), mi.getIndexSet(), marketEndAt);
            }
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
        // marketEndAt is now on market_info table — this backfill is no longer needed
        // since the migration already populated market_info from existing data.
        // Keeping as no-op for backward compatibility.
        log.debug("backfillPositionMarketEndAt is deprecated after market_info migration. Skipping.");
        return 0;
    }

    private int backfillOrderTokenAndPositionIds(int limit, boolean refreshCache) {
        // tokenId/indexSet are now on market_info table.
        // Only backfill positionId which still exists on the order table.
        List<SmartWalletOrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<SmartWalletOrderEntity>()
                .isNull(SmartWalletOrderEntity::getPositionId)
                .orderByAsc(SmartWalletOrderEntity::getId)
                .last("limit " + limit));
        marketInfoService.populateOrders(orders);
        int updated = 0;
        for (SmartWalletOrderEntity order : orders) {
            if (order.getPositionId() == null) {
                Long positionId = findPositionIdForOrder(order);
                if (positionId != null) {
                    order.setPositionId(positionId);
                    order.setUpdatedAt(new Date());
                    if (updateOrderChecked(order, "backfillOrderPositionId")) {
                        updated++;
                    }
                }
            }
        }
        return updated;
    }

    private int backfillFillTokenAndPositionIds(int limit, boolean refreshCache) {
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .isNull(SmartWalletFillEntity::getPositionId)
                .orderByAsc(SmartWalletFillEntity::getId)
                .last("limit " + limit));
        marketInfoService.populateFills(fills);
        int updated = 0;
        for (SmartWalletFillEntity fill : fills) {
            if (fill.getPositionId() == null) {
                SmartWalletOrderEntity order = fill.getOrderId() == null ? null : orderMapper.selectById(fill.getOrderId());
                Long positionId = order != null && order.getPositionId() != null
                        ? order.getPositionId()
                        : findPositionIdForFill(fill);
                if (positionId != null) {
                    fill.setPositionId(positionId);
                    fillMapper.updateById(fill);
                    updated++;
                }
            }
        }
        return updated;
    }

    private int backfillPositionTokenIds(int limit, boolean refreshCache) {
        log.debug("backfillPositionTokenIds is deprecated after market_info migration. Skipping.");
        return 0;
    }

    private int backfillOrderMarketEndAt(int limit, boolean refreshCache) {
        log.debug("backfillOrderMarketEndAt is deprecated after market_info migration. Skipping.");
        return 0;
    }

    private int backfillTaskMarketEndAt(int limit, boolean refreshCache) {
        log.debug("backfillTaskMarketEndAt is deprecated after market_info migration. Skipping.");
        return 0;
    }

    private String resolveOutcomeTokenId(String marketId,
                                         String questionHint,
                                         String outcome,
                                         boolean refreshCache) {
        if (!StringUtils.hasText(marketId) || !StringUtils.hasText(outcome)) {
            return null;
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    marketId,
                    refreshCache,
                    questionHint,
                    List.of(outcome)
            );
            if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
                return null;
            }
            return metadata.getOutcomeTokenIds().entrySet().stream()
                    .filter(entry -> StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue()))
                    .filter(entry -> outcome.trim().equalsIgnoreCase(entry.getKey().trim()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Failed to resolve tokenId for marketId={} outcome={}: {}", marketId, outcome, e.getMessage());
            return null;
        }
    }

    private record TokenIdAndIndexSet(String tokenId, Integer indexSet) {}
    private record TimelineLedgerEntry(SmartWalletCashLedgerEntity entity, LocalDateTime bucketAt, BigDecimal delta) {}

    private record TimelineBaseline(BigDecimal cumulativeLossPnlUsdc,
                                    BigDecimal cumulativeRealizedPnlUsdc,
                                    BigDecimal cumulativeFeeUsdc,
                                    BigDecimal cumulativeNetPnlUsdc) {}

    /** Resolves both tokenId and indexSet from metadata in a single fetch. */
    private TokenIdAndIndexSet resolveOutcomeTokenIdAndIndexSet(String marketId,
                                                                 String questionHint,
                                                                 String outcome,
                                                                 boolean refreshCache) {
        if (!StringUtils.hasText(marketId) || !StringUtils.hasText(outcome)) {
            return new TokenIdAndIndexSet(null, null);
        }
        try {
            PolymarketApiClient.MarketMetadata metadata = marketMetadataService.getBestEffortByConditionId(
                    marketId, refreshCache, questionHint, List.of(outcome));
            if (metadata == null || metadata.getOutcomeTokenIds() == null || metadata.getOutcomeTokenIds().isEmpty()) {
                return new TokenIdAndIndexSet(null, null);
            }
            String tokenId = metadata.getOutcomeTokenIds().entrySet().stream()
                    .filter(entry -> StringUtils.hasText(entry.getKey()) && StringUtils.hasText(entry.getValue()))
                    .filter(entry -> outcome.trim().equalsIgnoreCase(entry.getKey().trim()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            Integer indexSet = tokenId != null ? metadata.resolveIndexSet(tokenId) : null;
            return new TokenIdAndIndexSet(tokenId, indexSet);
        } catch (RuntimeException e) {
            log.debug("Failed to resolve tokenId/indexSet for marketId={} outcome={}: {}", marketId, outcome, e.getMessage());
            return new TokenIdAndIndexSet(null, null);
        }
    }

    private Long findPositionIdForOrder(SmartWalletOrderEntity order) {
        if (order == null) {
            return null;
        }
        marketInfoService.populate(order);
        List<SmartWalletFillEntity> fills = fillMapper.selectList(new LambdaQueryWrapper<SmartWalletFillEntity>()
                .eq(SmartWalletFillEntity::getOrderId, order.getId())
                .isNotNull(SmartWalletFillEntity::getPositionId)
                .orderByDesc(SmartWalletFillEntity::getFilledAt)
                .orderByDesc(SmartWalletFillEntity::getId)
                .last("limit 1"));
        if (fills != null && !fills.isEmpty() && fills.get(0).getPositionId() != null) {
            return fills.get(0).getPositionId();
        }
        return pickPositionId(loadPositionCandidates(
                order.getExecutionAddress(),
                order.getMarketId(),
                order.getTokenId(),
                order.getOutcome()
        ), order.getPlacedAt());
    }

    private Long findPositionIdForFill(SmartWalletFillEntity fill) {
        if (fill == null) {
            return null;
        }
        marketInfoService.populate(fill);
        return pickPositionId(loadPositionCandidates(
                fill.getExecutionAddress(),
                fill.getMarketId(),
                fill.getTokenId(),
                fill.getOutcome()
        ), fill.getFilledAt());
    }

    private List<SmartWalletPositionEntity> loadPositionCandidates(String executionAddress,
                                                                   String marketId,
                                                                   String tokenId,
                                                                   String outcome) {
        Long marketInfoId = marketInfoService.findIdByMarketIdAndOutcome(marketId, 
                StringUtils.hasText(tokenId) ? null : outcome);
        if (marketInfoId == null && StringUtils.hasText(outcome)) {
            marketInfoId = marketInfoService.findIdByMarketIdAndOutcome(marketId, outcome);
        }
        if (marketInfoId != null) {
            List<SmartWalletPositionEntity> candidates = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                    .eq(StringUtils.hasText(executionAddress), SmartWalletPositionEntity::getExecutionAddress, executionAddress)
                    .eq(SmartWalletPositionEntity::getMarketInfoId, marketInfoId)
                    .orderByDesc(SmartWalletPositionEntity::getOpenedAt)
                    .orderByDesc(SmartWalletPositionEntity::getId));
            if (candidates != null && !candidates.isEmpty()) {
                return candidates;
            }
        }
        // Broader fallback: all market_info_ids for this marketId
        List<Long> allIds = marketInfoService.findIdsByMarketId(marketId);
        if (allIds.isEmpty()) return List.of();
        List<SmartWalletPositionEntity> candidates = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(StringUtils.hasText(executionAddress), SmartWalletPositionEntity::getExecutionAddress, executionAddress)
                .in(SmartWalletPositionEntity::getMarketInfoId, allIds)
                .orderByDesc(SmartWalletPositionEntity::getOpenedAt)
                .orderByDesc(SmartWalletPositionEntity::getId));
        return candidates == null ? List.of() : candidates;
    }

    private Long pickPositionId(List<SmartWalletPositionEntity> candidates, Date eventTime) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (eventTime != null) {
            for (SmartWalletPositionEntity candidate : candidates) {
                Date openedAt = candidate.getOpenedAt();
                Date closedAt = candidate.getClosedAt();
                boolean opened = openedAt == null || !openedAt.after(eventTime);
                boolean notClosed = closedAt == null || !closedAt.before(eventTime);
                if (opened && notClosed) {
                    return candidate.getId();
                }
            }
        }
        return candidates.size() == 1 ? candidates.get(0).getId() : null;
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

    private SmartWalletDailyReportView toDailyReportView(SmartWalletDailyReportEntity report, DailyOutcomeBreakdown breakdown) {
        DailyOutcomeBreakdown effectiveBreakdown = breakdown != null && breakdown.hasOutcomeEntries()
                ? breakdown
                : DailyOutcomeBreakdown.fromStoredReport(report);
        return SmartWalletDailyReportView.builder()
                .id(report.getId())
                .reportDate(report.getReportDate())
                .executionAddress(report.getExecutionAddress())
                .totalOrders(report.getTotalOrders())
                .filledOrders(report.getFilledOrders())
                .totalVolumeUsdc(report.getTotalVolumeUsdc())
                .feeUsdc(report.getFeeUsdc())
                .profitPnlUsdc(effectiveBreakdown.profitPnlUsdc)
                .lossPnlUsdc(effectiveBreakdown.lossPnlUsdc)
                .realizedPnlUsdc(report.getRealizedPnlUsdc())
                .unrealizedPnlUsdc(report.getUnrealizedPnlUsdc())
                .netPnlUsdc(report.getNetPnlUsdc())
                .winningPositions(effectiveBreakdown.winningPositions)
                .losingPositions(effectiveBreakdown.losingPositions)
                .winRate(effectiveBreakdown.winRate())
                .conflictingMarkets(calculateDailyConflictSummary(report).conflictingMarkets())
                .conflictingPositions(calculateDailyConflictSummary(report).conflictingPositions())
                .conflictNetPnlUsdc(calculateDailyConflictSummary(report).conflictNetPnlUsdc())
                .build();
    }

    private Map<LocalDate, DailyOutcomeBreakdown> loadDailyOutcomeBreakdowns(List<SmartWalletDailyReportEntity> reports, String mode) {
        if (reports == null || reports.isEmpty()) {
            return Map.of();
        }
        LocalDate minDate = null;
        LocalDate maxDate = null;
        for (SmartWalletDailyReportEntity report : reports) {
            LocalDate reportDate = toLocalDate(report.getReportDate());
            if (reportDate == null) {
                continue;
            }
            if (minDate == null || reportDate.isBefore(minDate)) {
                minDate = reportDate;
            }
            if (maxDate == null || reportDate.isAfter(maxDate)) {
                maxDate = reportDate;
            }
        }
        if (minDate == null || maxDate == null) {
            return Map.of();
        }
        return loadDailyOutcomeBreakdowns(minDate, maxDate, mode);
    }

    private Map<LocalDate, DailyOutcomeBreakdown> loadDailyOutcomeBreakdowns(LocalDate fromDate, LocalDate toDate, String mode) {
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) {
            return Map.of();
        }
        ZoneId zoneId = ZoneId.systemDefault();
        Date start = Date.from(fromDate.atStartOfDay(zoneId).toInstant());
        Date endExclusive = Date.from(toDate.plusDays(1).atStartOfDay(zoneId).toInstant());
        List<SmartWalletCashLedgerEntity> entries = cashLedgerMapper.selectList(new LambdaQueryWrapper<SmartWalletCashLedgerEntity>()
                .eq(SmartWalletCashLedgerEntity::getEntryType, "REALIZED_PNL")
                .ge(SmartWalletCashLedgerEntity::getOccurredAt, start)
                .lt(SmartWalletCashLedgerEntity::getOccurredAt, endExclusive)
                .orderByAsc(SmartWalletCashLedgerEntity::getOccurredAt)
                .orderByAsc(SmartWalletCashLedgerEntity::getId));
        if (entries == null || entries.isEmpty()) {
            return Map.of();
        }
        Map<LocalDate, Map<String, BigDecimal>> grouped = new LinkedHashMap<>();
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (entry == null || entry.getOccurredAt() == null || !matchesDashboardMode(entry.getExecutionAddress(), mode)) {
                continue;
            }
            LocalDate reportDate = toLocalDate(entry.getOccurredAt());
            if (reportDate == null || reportDate.isBefore(fromDate) || reportDate.isAfter(toDate)) {
                continue;
            }
            grouped.computeIfAbsent(reportDate, key -> new LinkedHashMap<>())
                    .merge(outcomeReferenceKey(entry), ledgerPnlDelta(entry), BigDecimal::add);
        }
        Map<LocalDate, DailyOutcomeBreakdown> result = new LinkedHashMap<>();
        for (LocalDate cursor = fromDate; !cursor.isAfter(toDate); cursor = cursor.plusDays(1)) {
            result.put(cursor, deriveDailyOutcomeBreakdown(grouped.get(cursor)));
        }
        return result;
    }

    private DailyOutcomeBreakdown deriveDailyOutcomeBreakdown(List<SmartWalletCashLedgerEntity> entries) {
        if (entries == null || entries.isEmpty()) {
            return DailyOutcomeBreakdown.empty();
        }
        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        for (SmartWalletCashLedgerEntity entry : entries) {
            if (entry == null || !"REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
                continue;
            }
            grouped.merge(outcomeReferenceKey(entry), ledgerPnlDelta(entry), BigDecimal::add);
        }
        return deriveDailyOutcomeBreakdown(grouped);
    }

    private DailyOutcomeBreakdown deriveDailyOutcomeBreakdown(Map<String, BigDecimal> grouped) {
        if (grouped == null || grouped.isEmpty()) {
            return DailyOutcomeBreakdown.empty();
        }
        BigDecimal profit = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        int wins = 0;
        int losses = 0;
        for (BigDecimal pnl : grouped.values()) {
            BigDecimal signedPnl = safe(pnl);
            if (signedPnl.compareTo(BigDecimal.ZERO) > 0) {
                profit = profit.add(signedPnl);
                wins++;
            } else if (signedPnl.compareTo(BigDecimal.ZERO) < 0) {
                loss = loss.add(signedPnl);
                losses++;
            }
        }
        return new DailyOutcomeBreakdown(
                profit.setScale(8, RoundingMode.HALF_UP),
                loss.setScale(8, RoundingMode.HALF_UP),
                wins,
                losses
        );
    }

    private String outcomeReferenceKey(SmartWalletCashLedgerEntity entry) {
        if (entry == null) {
            return "ledger:null";
        }
        if (StringUtils.hasText(entry.getReferenceType()) && entry.getReferenceId() != null) {
            return entry.getReferenceType() + ":" + entry.getReferenceId();
        }
        if (entry.getReferenceId() != null) {
            return "ref:" + entry.getReferenceId();
        }
        if (entry.getId() != null) {
            return "ledger:" + entry.getId();
        }
        return "occurred:" + String.valueOf(entry.getOccurredAt());
    }

    private static final class DailyOutcomeBreakdown {
        private final BigDecimal profitPnlUsdc;
        private final BigDecimal lossPnlUsdc;
        private final int winningPositions;
        private final int losingPositions;

        private DailyOutcomeBreakdown(BigDecimal profitPnlUsdc,
                                      BigDecimal lossPnlUsdc,
                                      int winningPositions,
                                      int losingPositions) {
            this.profitPnlUsdc = profitPnlUsdc;
            this.lossPnlUsdc = lossPnlUsdc;
            this.winningPositions = winningPositions;
            this.losingPositions = losingPositions;
        }

        private static DailyOutcomeBreakdown empty() {
            return new DailyOutcomeBreakdown(
                    BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP),
                    BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP),
                    0,
                    0
            );
        }

        private static DailyOutcomeBreakdown fromStoredReport(SmartWalletDailyReportEntity report) {
            BigDecimal realized = report == null || report.getRealizedPnlUsdc() == null
                    ? BigDecimal.ZERO
                    : report.getRealizedPnlUsdc();
            return new DailyOutcomeBreakdown(
                    realized.max(BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP),
                    (realized.compareTo(BigDecimal.ZERO) < 0 ? realized : BigDecimal.ZERO).setScale(8, RoundingMode.HALF_UP),
                    report == null || report.getWinningPositions() == null ? 0 : report.getWinningPositions(),
                    report == null || report.getLosingPositions() == null ? 0 : report.getLosingPositions()
            );
        }

        private boolean hasOutcomeEntries() {
            return winningPositions > 0 || losingPositions > 0
                    || profitPnlUsdc.compareTo(BigDecimal.ZERO) != 0
                    || lossPnlUsdc.compareTo(BigDecimal.ZERO) != 0;
        }

        private BigDecimal winRate() {
            int completed = winningPositions + losingPositions;
            return completed == 0
                    ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(winningPositions).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
        }

    }

    private ConflictSummary calculateDailyConflictSummary(SmartWalletDailyReportEntity report) {
        if (report == null || report.getReportDate() == null) {
            return ConflictSummary.empty();
        }
        List<SmartWalletPositionEntity> positions = positionMapper.selectList(new LambdaQueryWrapper<SmartWalletPositionEntity>()
                .eq(StringUtils.hasText(report.getExecutionAddress()), SmartWalletPositionEntity::getExecutionAddress, report.getExecutionAddress()));
        marketInfoService.populatePositions(positions);
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

    private Map<String, String> buildPositionWalletMap(List<SmartWalletOrderEntity> orders, List<SmartWalletFillEntity> fills) {
        Map<String, String> grouped = new LinkedHashMap<>();
        if (orders != null) {
            List<SmartWalletOrderEntity> ordered = new ArrayList<>(orders);
            ordered.sort(Comparator.comparing(SmartWalletOrderEntity::getPlacedAt,
                            Comparator.nullsLast(Date::compareTo))
                    .thenComparing(SmartWalletOrderEntity::getId, Comparator.nullsLast(Long::compareTo)));
            for (SmartWalletOrderEntity order : ordered) {
                if (order == null || !StringUtils.hasText(order.getWalletAddress())) {
                    continue;
                }
                String normalizedWalletAddress = normalizeWalletAddress(order.getWalletAddress());
                grouped.put(positionWalletKey(order.getPositionId(), order.getExecutionAddress(), order.getMarketId(), order.getOutcome()),
                        normalizedWalletAddress);
                grouped.put(legacyPositionKey(order.getExecutionAddress(), order.getMarketId(), order.getOutcome()),
                        normalizedWalletAddress);
            }
        }
        if (fills != null) {
            List<SmartWalletFillEntity> ordered = new ArrayList<>(fills);
            ordered.sort(Comparator.comparing(SmartWalletFillEntity::getFilledAt,
                            Comparator.nullsLast(Date::compareTo))
                    .thenComparing(SmartWalletFillEntity::getId, Comparator.nullsLast(Long::compareTo)));
            for (SmartWalletFillEntity fill : ordered) {
                if (fill == null || !StringUtils.hasText(fill.getWalletAddress())) {
                    continue;
                }
                String normalizedWalletAddress = normalizeWalletAddress(fill.getWalletAddress());
                grouped.put(positionWalletKey(fill.getPositionId(), fill.getExecutionAddress(), fill.getMarketId(), fill.getOutcome()),
                        normalizedWalletAddress);
                grouped.put(legacyPositionKey(fill.getExecutionAddress(), fill.getMarketId(), fill.getOutcome()),
                        normalizedWalletAddress);
            }
        }
        return grouped;
    }

    private String resolvePositionWalletAddress(SmartWalletPositionEntity position, Map<String, String> positionWalletMap) {
        if (position == null) {
            return "UNKNOWN";
        }
        String key = positionWalletKey(position.getId(), position.getExecutionAddress(), position.getMarketId(), position.getOutcome());
        String walletAddress = positionWalletMap.get(key);
        if (walletAddress != null) {
            return walletAddress;
        }
        return positionWalletMap.getOrDefault(
                legacyPositionKey(position.getExecutionAddress(), position.getMarketId(), position.getOutcome()),
                "UNKNOWN"
        );
    }

    private String positionWalletKey(Long positionId, String executionAddress, String marketId, String outcome) {
        if (positionId != null) {
            return "position:" + positionId;
        }
        return legacyPositionKey(executionAddress, marketId, outcome);
    }

    private String legacyPositionKey(String executionAddress, String marketId, String outcome) {
        return normalizeWalletAddress(executionAddress)
                + "|"
                + (marketId == null ? "" : marketId.trim())
                + "|"
                + (outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT));
    }

    private Long firstNonNull(Long primary, Long fallback) {
        return primary != null ? primary : fallback;
    }

    private String normalizeWalletAddress(String walletAddress) {
        if (!StringUtils.hasText(walletAddress)) {
            return "UNKNOWN";
        }
        return walletAddress.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isWithin(Date value, Date startInclusive, Date endExclusive) {
        if (value == null || startInclusive == null || endExclusive == null) {
            return false;
        }
        return !value.before(startInclusive) && value.before(endExclusive);
    }

    private record PositionUpdateResult(SmartWalletPositionEntity position, BigDecimal realizedDelta) {
    }

    private record ExitOrderSummary(int earlyExitOrderCount,
                                    BigDecimal earlyExitRealizedPnlUsdc,
                                    int takeProfitOrderCount,
                                    BigDecimal takeProfitRealizedPnlUsdc,
                                    int stopLossOrderCount,
                                    BigDecimal stopLossRealizedPnlUsdc) {
    }

    private record DrawdownCalculation(BigDecimal maxDrawdown, boolean hasEvents) {
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
                                    Integer indexSet,
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
            if ("FEE".equalsIgnoreCase(entry.getEntryType())
                    || "NETWORK_FEE".equalsIgnoreCase(entry.getEntryType())) {
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

    private class FollowAddressDailyAccumulator {
        private final String walletAddress;
        private int totalOrders;
        private int filledOrders;
        private int openPositions;
        private int closedPositions;
        private int winningPositions;
        private int losingPositions;
        private int breakevenPositions;
        private final Set<String> distinctMarkets = new LinkedHashSet<>();
        private BigDecimal tradedVolumeUsdc = BigDecimal.ZERO;
        private BigDecimal grossExposureUsdc = BigDecimal.ZERO;
        private BigDecimal feeUsdc = BigDecimal.ZERO;
        private BigDecimal realizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal unrealizedPnlUsdc = BigDecimal.ZERO;
        private BigDecimal totalWinsUsdc = BigDecimal.ZERO;
        private BigDecimal totalLossesUsdc = BigDecimal.ZERO;
        private BigDecimal largestWinUsdc = BigDecimal.ZERO;
        private BigDecimal largestLossUsdc = BigDecimal.ZERO;

        private FollowAddressDailyAccumulator(String walletAddress) {
            this.walletAddress = walletAddress;
        }

        private void addOrder(SmartWalletOrderEntity order) {
            if (order == null) {
                return;
            }
            totalOrders++;
            if (StringUtils.hasText(order.getMarketId())) {
                distinctMarkets.add(order.getMarketId());
            }
        }

        private void addFill(SmartWalletFillEntity fill) {
            if (fill == null) {
                return;
            }
            filledOrders++;
            tradedVolumeUsdc = tradedVolumeUsdc.add(safe(fill.getNotionalUsdc()));
            feeUsdc = feeUsdc.add(safe(fill.getFeeUsdc()));
            if (StringUtils.hasText(fill.getMarketId())) {
                distinctMarkets.add(fill.getMarketId());
            }
        }

        private void addClosedPosition(SmartWalletPositionEntity position) {
            if (position == null) {
                return;
            }
            closedPositions++;
            BigDecimal realized = safe(position.getRealizedPnlUsdc()).setScale(8, RoundingMode.HALF_UP);
            realizedPnlUsdc = realizedPnlUsdc.add(realized);
            if (StringUtils.hasText(position.getMarketId())) {
                distinctMarkets.add(position.getMarketId());
            }
            if (realized.compareTo(BigDecimal.ZERO) > 0) {
                winningPositions++;
                totalWinsUsdc = totalWinsUsdc.add(realized);
                if (realized.compareTo(largestWinUsdc) > 0) {
                    largestWinUsdc = realized;
                }
            } else if (realized.compareTo(BigDecimal.ZERO) < 0) {
                losingPositions++;
                BigDecimal absoluteLoss = realized.abs();
                totalLossesUsdc = totalLossesUsdc.add(absoluteLoss);
                if (absoluteLoss.compareTo(largestLossUsdc.abs()) > 0) {
                    largestLossUsdc = realized;
                }
            } else {
                breakevenPositions++;
            }
        }

        private void addOpenPosition(SmartWalletPositionEntity position) {
            if (position == null) {
                return;
            }
            openPositions++;
            unrealizedPnlUsdc = unrealizedPnlUsdc.add(safe(position.getUnrealizedPnlUsdc()));
            grossExposureUsdc = grossExposureUsdc.add(safe(position.getCostBasisUsdc()));
            if (StringUtils.hasText(position.getMarketId())) {
                distinctMarkets.add(position.getMarketId());
            }
        }

        private BigDecimal netPnl() {
            return realizedPnlUsdc.add(unrealizedPnlUsdc).subtract(feeUsdc);
        }

        private SmartWalletFollowAddressDailyReportView toView(Date reportDate) {
            int completed = winningPositions + losingPositions;
            BigDecimal winRate = completed == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(winningPositions).divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
            BigDecimal averageWin = winningPositions == 0
                    ? BigDecimal.ZERO
                    : totalWinsUsdc.divide(BigDecimal.valueOf(winningPositions), 8, RoundingMode.HALF_UP);
            BigDecimal averageLoss = losingPositions == 0
                    ? BigDecimal.ZERO
                    : totalLossesUsdc.divide(BigDecimal.valueOf(losingPositions), 8, RoundingMode.HALF_UP);
            return SmartWalletFollowAddressDailyReportView.builder()
                    .reportDate(reportDate)
                    .walletAddress(walletAddress)
                    .totalOrders(totalOrders)
                    .filledOrders(filledOrders)
                    .openPositions(openPositions)
                    .closedPositions(closedPositions)
                    .winningPositions(winningPositions)
                    .losingPositions(losingPositions)
                    .breakevenPositions(breakevenPositions)
                    .distinctMarkets(distinctMarkets.size())
                    .tradedVolumeUsdc(tradedVolumeUsdc)
                    .grossExposureUsdc(grossExposureUsdc)
                    .feeUsdc(feeUsdc)
                    .realizedPnlUsdc(realizedPnlUsdc)
                    .unrealizedPnlUsdc(unrealizedPnlUsdc)
                    .netPnlUsdc(netPnl())
                    .winRate(winRate)
                    .averageWinUsdc(averageWin)
                    .averageLossUsdc(averageLoss)
                    .largestWinUsdc(largestWinUsdc)
                    .largestLossUsdc(largestLossUsdc)
                    .build();
        }
    }

    private class TimelineBucketAccumulator {
        private BigDecimal profitPnl = BigDecimal.ZERO;
        private BigDecimal lossPnl = BigDecimal.ZERO;
        private BigDecimal realizedPnl = BigDecimal.ZERO;
        private BigDecimal fee = BigDecimal.ZERO;

        private void add(SmartWalletCashLedgerEntity entry, BigDecimal pnlDelta) {
            if ("FEE".equalsIgnoreCase(entry.getEntryType())
                    || "NETWORK_FEE".equalsIgnoreCase(entry.getEntryType())) {
                fee = fee.add(safe(entry.getAmount()));
            } else if ("REALIZED_PNL".equalsIgnoreCase(entry.getEntryType())) {
                realizedPnl = realizedPnl.add(pnlDelta);
                if (pnlDelta.compareTo(BigDecimal.ZERO) > 0) {
                    profitPnl = profitPnl.add(pnlDelta);
                } else if (pnlDelta.compareTo(BigDecimal.ZERO) < 0) {
                    lossPnl = lossPnl.add(pnlDelta);
                }
            }
        }

        private BigDecimal profitPnl() {
            return profitPnl;
        }

        private BigDecimal lossPnl() {
            return lossPnl;
        }

        private BigDecimal netPnl() {
            return realizedPnl.subtract(fee);
        }

        private boolean hasActivity() {
            return profitPnl.compareTo(BigDecimal.ZERO) != 0
                    || lossPnl.compareTo(BigDecimal.ZERO) != 0
                    || realizedPnl.compareTo(BigDecimal.ZERO) != 0
                    || fee.compareTo(BigDecimal.ZERO) != 0;
        }
    }

    // ── 乐观锁工具方法 ──────────────────────────────────────────────────────

    /**
     * 带重试的 Position 更新。当 @Version 乐观锁导致 updateById 返回 0 时，
     * 重新读取最新数据，通过 mutator 重新应用业务变更，再次尝试更新。
     * 最多重试 {@code maxRetries} 次，全部失败后抛出 IllegalStateException。
     *
     * @param positionId 待更新的 position 主键
     * @param mutator    对最新 position 施加业务变更的 lambda
     * @param context    日志描述（如 "claimResolvedPosition"）
     * @param maxRetries 最大重试次数（不含首次）
     * @return 更新成功后的 position 实体
     */
    private SmartWalletPositionEntity updatePositionWithRetry(Long positionId,
                                                               Consumer<SmartWalletPositionEntity> mutator,
                                                               String context,
                                                               int maxRetries) {
        SmartWalletPositionEntity position = positionMapper.selectById(positionId);
        if (position == null) {
            throw new IllegalStateException(context + ": position not found, id=" + positionId);
        }
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            mutator.accept(position);
            position.setUpdatedAt(new Date());
            int affected = positionMapper.updateById(position);
            if (affected == 1) {
                return position;
            }
            log.warn("[OptimisticLock] {} position {} version conflict (attempt {}/{}), re-reading...",
                    context, positionId, attempt + 1, maxRetries + 1);
            position = positionMapper.selectById(positionId);
            if (position == null) {
                throw new IllegalStateException(context + ": position disappeared during retry, id=" + positionId);
            }
        }
        throw new IllegalStateException(context + ": optimistic lock failed after " + (maxRetries + 1)
                + " attempts for position id=" + positionId);
    }

    /**
     * 非关键 Position 更新——检查 updateById 返回值，版本冲突时仅打印 WARN 日志。
     * 适用于定时任务场景：本轮跳过，下一轮自然重试。
     */
    private boolean updatePositionChecked(SmartWalletPositionEntity position, String context) {
        int affected = positionMapper.updateById(position);
        if (affected == 0 && !"markToMarket".equals(context)) {
            log.warn("[OptimisticLock] {} position {} version conflict, skipping (will retry next cycle)",
                    context, position.getId());
        }
        return affected == 1;
    }

    /**
     * 非关键 Order 更新——检查 updateById 返回值，版本冲突时仅打印 WARN 日志。
     */
    private boolean updateOrderChecked(SmartWalletOrderEntity order, String context) {
        int affected = orderMapper.updateById(order);
        if (affected == 0) {
            log.warn("[OptimisticLock] {} order {} version conflict, skipping (will retry next cycle)",
                    context, order.getId());
        }
        return affected == 1;
    }
}
