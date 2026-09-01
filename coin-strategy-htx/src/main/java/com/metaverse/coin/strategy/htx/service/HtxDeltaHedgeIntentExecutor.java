package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxHedgeRecycleRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxDeltaHedgeLedgerEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxInventoryRecycleSagaEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxDeltaHedgeLedgerMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxInventoryRecycleSagaMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Default-closed sender for persisted moving-grid hedge/recycle intents.
 *
 * <p>The service is deliberately not scheduled. A caller must explicitly choose rows to advance.
 * Real exchange sends require {@code htx.contract.dry-run=false} and
 * {@code htx.contract.live-enabled=true}; otherwise the row is closed as skipped without calling
 * the private trade API.</p>
 */
@Slf4j
@Service
@ConditionalOnBean(HtxUsdtSwapApiService.class)
public class HtxDeltaHedgeIntentExecutor {

    private static final String ACTION_OPEN_SHORT = "OPEN_SHORT";
    private static final String ACTION_CLOSE_SHORT = "CLOSE_SHORT";
    private static final String SIDE_OPEN_SHORT = "sell";
    private static final String SIDE_CLOSE_SHORT = "buy";
    private static final String POSITION_SIDE_SHORT = "short";
    private static final String MARKET_TYPE = "market";
    private static final String IOC = "ioc";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MAX_REASON_LENGTH = 512;

    private final HtxUsdtSwapService swap;
    private final HtxContractProperties props;
    private final HtxDeltaHedgeLedgerMapper hedgeMapper;
    private final HtxInventoryRecycleSagaMapper recycleMapper;

    @Autowired
    public HtxDeltaHedgeIntentExecutor(HtxUsdtSwapApiService api,
                                       HtxContractProperties props,
                                       HtxDeltaHedgeLedgerMapper hedgeMapper,
                                       HtxInventoryRecycleSagaMapper recycleMapper) {
        this(api.getSwapService(), props, hedgeMapper, recycleMapper);
    }

    HtxDeltaHedgeIntentExecutor(HtxUsdtSwapService swap,
                                HtxContractProperties props,
                                HtxDeltaHedgeLedgerMapper hedgeMapper,
                                HtxInventoryRecycleSagaMapper recycleMapper) {
        this.swap = swap;
        this.props = props;
        this.hedgeMapper = hedgeMapper;
        this.recycleMapper = recycleMapper;
    }

    public List<ExecutionResult> executePendingHedgeIntents(int limit) {
        int safeLimit = Math.max(1, limit);
        List<HtxDeltaHedgeLedgerEntity> rows =
                hedgeMapper.findByStatus(HtxHedgeRecycleRecorder.STATUS_INTENT, safeLimit);
        List<ExecutionResult> results = new ArrayList<>(rows.size());
        for (HtxDeltaHedgeLedgerEntity row : rows) {
            results.add(executeHedgeIntent(row));
        }
        return results;
    }

    public ExecutionResult executeHedgeIntent(long id) {
        HtxDeltaHedgeLedgerEntity row = hedgeMapper.findById(id);
        if (row == null) {
            return ExecutionResult.notFound(ExecutionKind.HEDGE, id);
        }
        return executeHedgeIntent(row);
    }

    public List<ExecutionResult> executePendingRecycleIntents(int limit) {
        int safeLimit = Math.max(1, limit);
        List<HtxInventoryRecycleSagaEntity> rows =
                recycleMapper.findByStatus(HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING, safeLimit);
        List<ExecutionResult> results = new ArrayList<>(rows.size());
        for (HtxInventoryRecycleSagaEntity row : rows) {
            results.add(executeRecycleCloseIntent(row));
        }
        return results;
    }

    public ExecutionResult executeRecycleCloseIntent(long id) {
        HtxInventoryRecycleSagaEntity row = recycleMapper.findById(id);
        if (row == null) {
            return ExecutionResult.notFound(ExecutionKind.RECYCLE_CLOSE, id);
        }
        return executeRecycleCloseIntent(row);
    }

    public List<ExecutionResult> reconcileSentHedgeIntents(int limit) {
        int safeLimit = Math.max(1, limit);
        List<HtxDeltaHedgeLedgerEntity> rows =
                hedgeMapper.findByStatus(HtxHedgeRecycleRecorder.STATUS_SENT, safeLimit);
        List<ExecutionResult> results = new ArrayList<>(rows.size());
        for (HtxDeltaHedgeLedgerEntity row : rows) {
            results.add(reconcileHedgeIntent(row));
        }
        return results;
    }

    public ExecutionResult reconcileHedgeIntent(long id) {
        HtxDeltaHedgeLedgerEntity row = hedgeMapper.findById(id);
        if (row == null) {
            return ExecutionResult.notFound(ExecutionKind.HEDGE, id);
        }
        return reconcileHedgeIntent(row);
    }

    public List<ExecutionResult> reconcileSentRecycleIntents(int limit) {
        int safeLimit = Math.max(1, limit);
        List<HtxInventoryRecycleSagaEntity> rows =
                recycleMapper.findByStatus(HtxHedgeRecycleRecorder.STATUS_SENT, safeLimit);
        List<ExecutionResult> results = new ArrayList<>(rows.size());
        for (HtxInventoryRecycleSagaEntity row : rows) {
            results.add(reconcileRecycleCloseIntent(row));
        }
        return results;
    }

    public ExecutionResult reconcileRecycleCloseIntent(long id) {
        HtxInventoryRecycleSagaEntity row = recycleMapper.findById(id);
        if (row == null) {
            return ExecutionResult.notFound(ExecutionKind.RECYCLE_CLOSE, id);
        }
        return reconcileRecycleCloseIntent(row);
    }

    public RecoveryBacklog recoveryBacklog() {
        return new RecoveryBacklog(
                hedgeMapper.countByStatus(HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED),
                recycleMapper.countByStatus(HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED),
                hedgeMapper.countByStatus(HtxHedgeRecycleRecorder.STATUS_UNKNOWN),
                recycleMapper.countByStatus(HtxHedgeRecycleRecorder.STATUS_UNKNOWN));
    }

    public PendingBacklog pendingBacklog() {
        return new PendingBacklog(
                hedgeMapper.countByStatus(HtxHedgeRecycleRecorder.STATUS_INTENT),
                recycleMapper.countByStatus(HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING));
    }

    public ExecutionResult executeHedgeIntent(HtxDeltaHedgeLedgerEntity row) {
        if (row == null || row.getId() == null) {
            return ExecutionResult.notFound(ExecutionKind.HEDGE, null);
        }
        if (!HtxHedgeRecycleRecorder.STATUS_INTENT.equals(row.getStatus())) {
            return ExecutionResult.stale(ExecutionKind.HEDGE, row.getId(), "status=" + row.getStatus());
        }
        String invalidReason = validateHedge(row);
        if (invalidReason != null) {
            return finishHedge(row.getId(), HtxHedgeRecycleRecorder.STATUS_INTENT,
                    HtxHedgeRecycleRecorder.STATUS_INVALID_INTENT, invalidReason,
                    ExecutionCode.INVALID_INTENT);
        }
        ExecutionResult gate = closeByLiveGate(ExecutionKind.HEDGE, row.getId(),
                HtxHedgeRecycleRecorder.STATUS_INTENT);
        if (gate != null) {
            return gate;
        }
        if (!claimHedge(row.getId(), HtxHedgeRecycleRecorder.STATUS_INTENT)) {
            return ExecutionResult.stale(ExecutionKind.HEDGE, row.getId(), "intent already claimed");
        }
        HtxSwapOrderRequest request = hedgeRequest(row);
        return sendHedge(row.getId(), request);
    }

    public ExecutionResult reconcileHedgeIntent(HtxDeltaHedgeLedgerEntity row) {
        if (row == null || row.getId() == null) {
            return ExecutionResult.notFound(ExecutionKind.HEDGE, null);
        }
        if (!HtxHedgeRecycleRecorder.STATUS_SENT.equals(row.getStatus())) {
            return ExecutionResult.stale(ExecutionKind.HEDGE, row.getId(), "status=" + row.getStatus());
        }
        if (row.getExchangeOrderId() == null && blank(row.getClientOrderId())) {
            return reconcileHedge(row, null, HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED,
                    "sent hedge missing exchange and client order id; manual reconciliation required",
                    ExecutionCode.HEDGE_DEGRADED);
        }
        HtxSwapOrder order;
        try {
            QueryResult queried = queryOrder(row.getContractCode(), row.getExchangeOrderId(),
                    row.getClientOrderId());
            if (!queried.ok()) {
                return reconcileHedge(row, null, HtxHedgeRecycleRecorder.STATUS_UNKNOWN,
                        queried.reason(), ExecutionCode.UNKNOWN);
            }
            order = queried.order();
        } catch (Exception ex) {
            log.error("hedge intent reconcile failed id={} contract={} orderId={}",
                    row.getId(), row.getContractCode(), row.getExchangeOrderId(), ex);
            return reconcileHedge(row, null, HtxHedgeRecycleRecorder.STATUS_UNKNOWN,
                    "query exception: " + ex.getMessage(), ExecutionCode.UNKNOWN);
        }
        return reconcileHedge(row, order, terminalStatus(row.getContractVolume(), order),
                terminalReason(row.getContractVolume(), order), terminalCode(row.getContractVolume(), order));
    }

    public ExecutionResult executeRecycleCloseIntent(HtxInventoryRecycleSagaEntity row) {
        if (row == null || row.getId() == null) {
            return ExecutionResult.notFound(ExecutionKind.RECYCLE_CLOSE, null);
        }
        if (!HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING.equals(row.getStatus())) {
            return ExecutionResult.stale(ExecutionKind.RECYCLE_CLOSE, row.getId(),
                    "status=" + row.getStatus());
        }
        String invalidReason = validateRecycle(row);
        if (invalidReason != null) {
            return finishRecycle(row.getId(), HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING,
                    HtxHedgeRecycleRecorder.STATUS_INVALID_INTENT, invalidReason,
                    ExecutionCode.INVALID_INTENT);
        }
        ExecutionResult gate = closeByLiveGate(ExecutionKind.RECYCLE_CLOSE, row.getId(),
                HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING);
        if (gate != null) {
            return gate;
        }
        if (!claimRecycle(row.getId(), HtxHedgeRecycleRecorder.STATUS_CLOSE_SHORT_PENDING)) {
            return ExecutionResult.stale(ExecutionKind.RECYCLE_CLOSE, row.getId(),
                    "recycle intent already claimed");
        }
        HtxSwapOrderRequest request = recycleRequest(row);
        return sendRecycle(row.getId(), request);
    }

    public ExecutionResult reconcileRecycleCloseIntent(HtxInventoryRecycleSagaEntity row) {
        if (row == null || row.getId() == null) {
            return ExecutionResult.notFound(ExecutionKind.RECYCLE_CLOSE, null);
        }
        if (!HtxHedgeRecycleRecorder.STATUS_SENT.equals(row.getStatus())) {
            return ExecutionResult.stale(ExecutionKind.RECYCLE_CLOSE, row.getId(),
                    "status=" + row.getStatus());
        }
        if (row.getCloseExchangeOrderId() == null && blank(row.getCloseClientOrderId())) {
            return reconcileRecycle(row, null, HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED,
                    "sent recycle close missing exchange and client order id; manual reconciliation required",
                    ExecutionCode.HEDGE_DEGRADED);
        }
        HtxSwapOrder order;
        try {
            QueryResult queried = queryOrder(row.getContractCode(), row.getCloseExchangeOrderId(),
                    row.getCloseClientOrderId());
            if (!queried.ok()) {
                return reconcileRecycle(row, null, HtxHedgeRecycleRecorder.STATUS_UNKNOWN,
                        queried.reason(), ExecutionCode.UNKNOWN);
            }
            order = queried.order();
        } catch (Exception ex) {
            log.error("recycle close reconcile failed id={} contract={} orderId={}",
                    row.getId(), row.getContractCode(), row.getCloseExchangeOrderId(), ex);
            return reconcileRecycle(row, null, HtxHedgeRecycleRecorder.STATUS_UNKNOWN,
                    "query exception: " + ex.getMessage(), ExecutionCode.UNKNOWN);
        }
        return reconcileRecycle(row, order, terminalStatus(row.getCloseContracts(), order),
                terminalReason(row.getCloseContracts(), order), terminalCode(row.getCloseContracts(), order));
    }

    private ExecutionResult closeByLiveGate(ExecutionKind kind, long id, String expectedStatus) {
        if (props.isDryRun()) {
            return finish(kind, id, expectedStatus, HtxHedgeRecycleRecorder.STATUS_SKIPPED_DRY_RUN,
                    "htx.contract.dry-run=true", ExecutionCode.SKIPPED_DRY_RUN);
        }
        if (!props.isLiveEnabled()) {
            return finish(kind, id, expectedStatus, HtxHedgeRecycleRecorder.STATUS_SKIPPED_LIVE_GATE,
                    "htx.contract.live-enabled=false", ExecutionCode.SKIPPED_LIVE_GATE);
        }
        return null;
    }

    private boolean claimHedge(long id, String expectedStatus) {
        return hedgeMapper.updateStatusIfCurrent(id, expectedStatus,
                HtxHedgeRecycleRecorder.STATUS_SENDING, "claimed for hedge send") == 1;
    }

    private boolean claimRecycle(long id, String expectedStatus) {
        return recycleMapper.updateStatusIfCurrent(id, expectedStatus,
                HtxHedgeRecycleRecorder.STATUS_SENDING, "claimed for recycle close send") == 1;
    }

    private ExecutionResult sendHedge(long id, HtxSwapOrderRequest request) {
        try {
            return finishFromResponse(ExecutionKind.HEDGE, id, swap.placeOrder(request));
        } catch (Exception ex) {
            log.error("hedge intent send failed id={} contract={} clientOrderId={}",
                    id, request.getContractCode(), request.getClientOrderId(), ex);
            return finishHedge(id, HtxHedgeRecycleRecorder.STATUS_SENDING,
                    HtxHedgeRecycleRecorder.STATUS_UNKNOWN, "send exception: " + ex.getMessage(),
                    ExecutionCode.UNKNOWN);
        }
    }

    private ExecutionResult sendRecycle(long id, HtxSwapOrderRequest request) {
        try {
            return finishFromResponse(ExecutionKind.RECYCLE_CLOSE, id, swap.placeOrder(request));
        } catch (Exception ex) {
            log.error("recycle close-short send failed id={} contract={} clientOrderId={}",
                    id, request.getContractCode(), request.getClientOrderId(), ex);
            return finishRecycle(id, HtxHedgeRecycleRecorder.STATUS_SENDING,
                    HtxHedgeRecycleRecorder.STATUS_UNKNOWN, "send exception: " + ex.getMessage(),
                    ExecutionCode.UNKNOWN);
        }
    }

    private ExecutionResult finishFromResponse(ExecutionKind kind, long id,
                                               HtxRestResponse<HtxSwapOrderResult> response) {
        HtxSwapOrderResult item = response == null ? null : response.getData();
        boolean accepted = response != null && response.ok() && item != null && item.getOrderId() != null
                && (item.getCode() == null || Integer.valueOf(200).equals(item.getCode()));
        if (accepted) {
            return finishAccepted(kind, id, item, acceptedReason(item));
        }
        String reason = exchangeReason(response, item);
        boolean explicitReject = response != null && response.ok() && item != null && item.getCode() != null
                && !Integer.valueOf(200).equals(item.getCode());
        return finish(kind, id, HtxHedgeRecycleRecorder.STATUS_SENDING,
                explicitReject ? HtxHedgeRecycleRecorder.STATUS_REJECTED : HtxHedgeRecycleRecorder.STATUS_UNKNOWN,
                reason, explicitReject ? ExecutionCode.REJECTED : ExecutionCode.UNKNOWN);
    }

    private ExecutionResult finishAccepted(ExecutionKind kind, long id, HtxSwapOrderResult item,
                                           String reason) {
        String exchangeClientOrderId = item.getClientOrderId() == null
                ? null : item.getClientOrderId().toString();
        int updated;
        if (ExecutionKind.HEDGE.equals(kind)) {
            updated = hedgeMapper.updateAcceptedIfCurrent(id, HtxHedgeRecycleRecorder.STATUS_SENDING,
                    HtxHedgeRecycleRecorder.STATUS_SENT, item.getOrderId(), exchangeClientOrderId,
                    clip(reason));
        } else {
            updated = recycleMapper.updateAcceptedIfCurrent(id, HtxHedgeRecycleRecorder.STATUS_SENDING,
                    HtxHedgeRecycleRecorder.STATUS_SENT, item.getOrderId(), exchangeClientOrderId,
                    clip(reason));
        }
        if (updated != 1) {
            return ExecutionResult.stale(kind, id, "status changed before SENT");
        }
        return new ExecutionResult(kind, ExecutionCode.SENT, id,
                HtxHedgeRecycleRecorder.STATUS_SENT, reason);
    }

    private ExecutionResult reconcileHedge(HtxDeltaHedgeLedgerEntity row, HtxSwapOrder order,
                                           String nextStatus, String reason, ExecutionCode code) {
        Long exchangeOrderId = order == null ? row.getExchangeOrderId() : firstNonNull(
                order.getOrderId(), row.getExchangeOrderId());
        String exchangeClientOrderId = order == null ? row.getExchangeClientOrderId()
                : firstNonBlank(order.getClientOrderId() == null ? null : order.getClientOrderId().toString(),
                row.getExchangeClientOrderId(), null);
        BigDecimal filledContracts = order == null ? nz(row.getFilledContracts()) : nz(order.getTradeVolume());
        int updated = hedgeMapper.updateReconciliationIfCurrent(row.getId(),
                HtxHedgeRecycleRecorder.STATUS_SENT, nextStatus, exchangeOrderId, exchangeClientOrderId,
                exchangeState(order), filledContracts, order == null ? null : order.getTradeAveragePrice(),
                order == null ? null : order.getFee(), order == null ? null : order.getFeeCurrency(),
                realizedProfit(order), clip(reason));
        if (updated != 1) {
            return ExecutionResult.stale(ExecutionKind.HEDGE, row.getId(),
                    "status changed before " + nextStatus);
        }
        return new ExecutionResult(ExecutionKind.HEDGE, code, row.getId(), nextStatus, reason);
    }

    private ExecutionResult reconcileRecycle(HtxInventoryRecycleSagaEntity row, HtxSwapOrder order,
                                             String nextStatus, String reason, ExecutionCode code) {
        Long exchangeOrderId = order == null ? row.getCloseExchangeOrderId() : firstNonNull(
                order.getOrderId(), row.getCloseExchangeOrderId());
        String exchangeClientOrderId = order == null ? row.getCloseExchangeClientOrderId()
                : firstNonBlank(order.getClientOrderId() == null ? null : order.getClientOrderId().toString(),
                row.getCloseExchangeClientOrderId(), null);
        BigDecimal filledContracts = order == null ? nz(row.getCloseFilledContracts())
                : nz(order.getTradeVolume());
        int updated = recycleMapper.updateReconciliationIfCurrent(row.getId(),
                HtxHedgeRecycleRecorder.STATUS_SENT, nextStatus, exchangeOrderId, exchangeClientOrderId,
                exchangeState(order), filledContracts, order == null ? null : order.getTradeAveragePrice(),
                order == null ? null : order.getFee(), order == null ? null : order.getFeeCurrency(),
                realizedProfit(order), clip(reason));
        if (updated != 1) {
            return ExecutionResult.stale(ExecutionKind.RECYCLE_CLOSE, row.getId(),
                    "status changed before " + nextStatus);
        }
        return new ExecutionResult(ExecutionKind.RECYCLE_CLOSE, code, row.getId(), nextStatus, reason);
    }

    private ExecutionResult finish(ExecutionKind kind, long id, String expectedStatus,
                                   String nextStatus, String reason, ExecutionCode code) {
        if (ExecutionKind.HEDGE.equals(kind)) {
            return finishHedge(id, expectedStatus, nextStatus, reason, code);
        }
        return finishRecycle(id, expectedStatus, nextStatus, reason, code);
    }

    private ExecutionResult finishHedge(long id, String expectedStatus, String nextStatus,
                                        String reason, ExecutionCode code) {
        int updated = hedgeMapper.updateStatusIfCurrent(id, expectedStatus, nextStatus, clip(reason));
        if (updated != 1) {
            return ExecutionResult.stale(ExecutionKind.HEDGE, id, "status changed before " + nextStatus);
        }
        return new ExecutionResult(ExecutionKind.HEDGE, code, id, nextStatus, reason);
    }

    private ExecutionResult finishRecycle(long id, String expectedStatus, String nextStatus,
                                          String reason, ExecutionCode code) {
        int updated = recycleMapper.updateStatusIfCurrent(id, expectedStatus, nextStatus, clip(reason));
        if (updated != 1) {
            return ExecutionResult.stale(ExecutionKind.RECYCLE_CLOSE, id,
                    "status changed before " + nextStatus);
        }
        return new ExecutionResult(ExecutionKind.RECYCLE_CLOSE, code, id, nextStatus, reason);
    }

    private HtxSwapOrderRequest hedgeRequest(HtxDeltaHedgeLedgerEntity row) {
        return HtxSwapOrderRequest.builder()
                .contractCode(row.getContractCode())
                .marginMode(props.getMarginMode())
                .positionSide(row.getPositionSide())
                .side(row.getSide())
                .type(MARKET_TYPE)
                .clientOrderId(row.getClientOrderId())
                .volume(Long.toString(row.getContractVolume()))
                .reduceOnly(Boolean.TRUE.equals(row.getReduceOnly()))
                .timeInForce(IOC)
                .build();
    }

    private HtxSwapOrderRequest recycleRequest(HtxInventoryRecycleSagaEntity row) {
        return HtxSwapOrderRequest.builder()
                .contractCode(row.getContractCode())
                .marginMode(props.getMarginMode())
                .positionSide(POSITION_SIDE_SHORT)
                .side(SIDE_CLOSE_SHORT)
                .type(MARKET_TYPE)
                .clientOrderId(row.getCloseClientOrderId())
                .volume(Long.toString(row.getCloseContracts()))
                .reduceOnly(true)
                .timeInForce(IOC)
                .build();
    }

    private QueryResult queryOrder(String contractCode, Long orderId, String clientOrderId) {
        HtxRestResponse<List<HtxSwapOrder>> response = orderId != null
                ? swap.queryOrder(contractCode, orderId)
                : swap.queryOrderByClientOrderId(contractCode, clientOrderId);
        if (response == null) {
            return QueryResult.failed("exchange query response missing");
        }
        if (!response.ok()) {
            return QueryResult.failed("exchange query not ok: " + firstNonBlank(
                    response.getMessage(), response.getErrMsg(), response.getErrCode()));
        }
        List<HtxSwapOrder> orders = response.getData();
        if (orders == null || orders.isEmpty() || orders.get(0) == null) {
            return QueryResult.failed("exchange order snapshot missing orderId=" + orderId
                    + " clientOrderId=" + clientOrderId);
        }
        return QueryResult.found(orders.get(0));
    }

    private static String terminalStatus(Long requestedContracts, HtxSwapOrder order) {
        if (fullyFilled(requestedContracts, order)) {
            return HtxHedgeRecycleRecorder.STATUS_FILLED;
        }
        if (nz(order.getTradeVolume()).signum() > 0 || terminal(order)) {
            return HtxHedgeRecycleRecorder.STATUS_HEDGE_DEGRADED;
        }
        return HtxHedgeRecycleRecorder.STATUS_SENT;
    }

    private static ExecutionCode terminalCode(Long requestedContracts, HtxSwapOrder order) {
        if (fullyFilled(requestedContracts, order)) {
            return ExecutionCode.FILLED;
        }
        if (nz(order.getTradeVolume()).signum() > 0 || terminal(order)) {
            return ExecutionCode.HEDGE_DEGRADED;
        }
        return ExecutionCode.STILL_OPEN;
    }

    private static String terminalReason(Long requestedContracts, HtxSwapOrder order) {
        String base = "exchange state=" + exchangeState(order)
                + " orderId=" + order.getOrderId()
                + " filled=" + nz(order.getTradeVolume()).toPlainString()
                + " requested=" + (requestedContracts == null ? "0" : requestedContracts);
        if (fullyFilled(requestedContracts, order)) {
            return base + " confirmed filled";
        }
        if (nz(order.getTradeVolume()).signum() > 0) {
            return base + " partial fill; hedge degraded until planner rechecks real position";
        }
        if (terminal(order)) {
            return base + " terminal without fill; hedge degraded";
        }
        return base + " still open";
    }

    private static boolean fullyFilled(Long requestedContracts, HtxSwapOrder order) {
        if ("filled".equalsIgnoreCase(exchangeState(order)) || Integer.valueOf(6).equals(order.getStatus())) {
            return true;
        }
        BigDecimal requested = requestedContracts == null ? ZERO : BigDecimal.valueOf(requestedContracts);
        return requested.signum() > 0 && nz(order.getTradeVolume()).compareTo(requested) >= 0;
    }

    private static boolean terminal(HtxSwapOrder order) {
        String state = exchangeState(order);
        return "partially_canceled".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state)
                || "rejected".equalsIgnoreCase(state)
                || Integer.valueOf(5).equals(order.getStatus())
                || Integer.valueOf(7).equals(order.getStatus());
    }

    private static String exchangeState(HtxSwapOrder order) {
        if (order == null) {
            return null;
        }
        if (!blank(order.getState())) {
            return order.getState();
        }
        if (Integer.valueOf(6).equals(order.getStatus())) {
            return "filled";
        }
        if (Integer.valueOf(5).equals(order.getStatus())) {
            return "canceled";
        }
        if (Integer.valueOf(7).equals(order.getStatus())) {
            return "rejected";
        }
        if (Integer.valueOf(4).equals(order.getStatus())) {
            return "partially_filled";
        }
        return "new";
    }

    private static BigDecimal realizedProfit(HtxSwapOrder order) {
        if (order == null) {
            return null;
        }
        return order.getRealProfit() != null ? order.getRealProfit() : order.getProfit();
    }

    private static String validateHedge(HtxDeltaHedgeLedgerEntity row) {
        if (blank(row.getContractCode())) {
            return "contract code missing";
        }
        if (blank(row.getClientOrderId())) {
            return "client order id missing";
        }
        if (row.getContractVolume() == null || row.getContractVolume() <= 0L) {
            return "contract volume must be positive";
        }
        if (!same(POSITION_SIDE_SHORT, row.getPositionSide())) {
            return "only short position side is owned by moving-grid hedge";
        }
        if (same(ACTION_OPEN_SHORT, row.getAction())) {
            if (!same(SIDE_OPEN_SHORT, row.getSide()) || Boolean.TRUE.equals(row.getReduceOnly())) {
                return "open-short must be sell/short without reduce-only";
            }
            return null;
        }
        if (same(ACTION_CLOSE_SHORT, row.getAction())) {
            if (!same(SIDE_CLOSE_SHORT, row.getSide()) || !Boolean.TRUE.equals(row.getReduceOnly())) {
                return "close-short must be buy/short reduce-only";
            }
            return null;
        }
        return "unsupported hedge action";
    }

    private static String validateRecycle(HtxInventoryRecycleSagaEntity row) {
        if (blank(row.getContractCode())) {
            return "contract code missing";
        }
        if (blank(row.getCloseClientOrderId())) {
            return "close client order id missing";
        }
        if (row.getCloseContracts() == null || row.getCloseContracts() <= 0L) {
            return "close contracts must be positive";
        }
        return null;
    }

    private static String acceptedReason(HtxSwapOrderResult item) {
        return "accepted orderId=" + item.getOrderId() + " clientOrderId=" + item.getClientOrderId();
    }

    private static String exchangeReason(HtxRestResponse<HtxSwapOrderResult> response,
                                         HtxSwapOrderResult item) {
        if (response == null) {
            return "exchange response missing";
        }
        if (!response.ok()) {
            return "exchange response not ok: " + firstNonBlank(response.getMessage(), response.getErrMsg(),
                    response.getErrCode());
        }
        if (item == null) {
            return "exchange order result missing";
        }
        return "exchange rejected: code=" + item.getCode() + " message=" + item.getMessage();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean same(String expected, String actual) {
        return expected.equalsIgnoreCase(actual == null ? "" : actual.trim());
    }

    private static String firstNonBlank(String first, String second, String third) {
        if (!blank(first)) {
            return first;
        }
        if (!blank(second)) {
            return second;
        }
        return blank(third) ? "unknown" : third;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }

    private static String clip(String value) {
        if (value == null || value.length() <= MAX_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_REASON_LENGTH);
    }

    public enum ExecutionKind {
        HEDGE,
        RECYCLE_CLOSE
    }

    public enum ExecutionCode {
        NOT_FOUND,
        STALE,
        SKIPPED_DRY_RUN,
        SKIPPED_LIVE_GATE,
        INVALID_INTENT,
        SENT,
        STILL_OPEN,
        FILLED,
        HEDGE_DEGRADED,
        REJECTED,
        UNKNOWN
    }

    private record QueryResult(boolean ok, HtxSwapOrder order, String reason) {
        static QueryResult found(HtxSwapOrder order) {
            return new QueryResult(true, order, null);
        }

        static QueryResult failed(String reason) {
            return new QueryResult(false, null, reason);
        }
    }

    public record ExecutionResult(ExecutionKind kind, ExecutionCode code, Long id,
                                  String status, String reason) {
        static ExecutionResult notFound(ExecutionKind kind, Long id) {
            return new ExecutionResult(kind, ExecutionCode.NOT_FOUND, id, null, "intent not found");
        }

        static ExecutionResult stale(ExecutionKind kind, Long id, String reason) {
            return new ExecutionResult(kind, ExecutionCode.STALE, id, null, reason);
        }
    }

    public record RecoveryBacklog(int degradedHedgeIntents, int degradedRecycleSagas,
                                  int unknownHedgeIntents, int unknownRecycleSagas) {
        public boolean blocksAutoSend() {
            return degradedHedgeIntents > 0 || degradedRecycleSagas > 0
                    || unknownHedgeIntents > 0 || unknownRecycleSagas > 0;
        }

        public int totalBlockingRows() {
            return degradedHedgeIntents + degradedRecycleSagas
                    + unknownHedgeIntents + unknownRecycleSagas;
        }
    }

    public record PendingBacklog(int hedgeIntents, int recycleCloseSagas) {
        public int totalRows() {
            return hedgeIntents + recycleCloseSagas;
        }
    }
}
