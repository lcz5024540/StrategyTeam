package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAvailableLeverage;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOpenOrders;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderRequest;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxContractTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractCycleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxContractLegEntity;
import com.metaverse.coin.strategy.htx.strategy.contract.ContractGridStrategy;
import com.metaverse.coin.strategy.htx.strategy.contract.FeeAwareContractGridStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Guarded isolated USDT-margined perpetual execution.
 *
 * <p>The legacy long-cycle endpoint remains available, while dual-cycle submits long and short
 * legs in V5 batch requests. Both modes refuse to open when an unrelated order or position exists.</p>
 */
@Slf4j
@Service
@ConditionalOnBean(HtxUsdtSwapApiService.class)
public class HtxContractTradingService {

    private final HtxUsdtSwapApiService api;
    private final HtxDepthGuard depthGuard;
    private final HtxContractProperties props;
    private final HtxContractTradeRecorder recorder;
    private final ContractGridStrategy gridStrategy;
    private final Map<String, ReentrantLock> contractLocks = new ConcurrentHashMap<>();

    @Autowired
    public HtxContractTradingService(HtxUsdtSwapApiService api,
                                     HtxDepthGuard depthGuard,
                                     HtxContractProperties props,
                                     HtxContractTradeRecorder recorder,
                                     ContractGridStrategy gridStrategy) {
        this.api = api;
        this.depthGuard = depthGuard;
        this.props = props;
        this.recorder = recorder;
        this.gridStrategy = gridStrategy;
    }

    HtxContractTradingService(HtxUsdtSwapApiService api,
                              HtxDepthGuard depthGuard,
                              HtxContractProperties props,
                              HtxContractTradeRecorder recorder) {
        this(api, depthGuard, props, recorder, new FeeAwareContractGridStrategy());
    }

    public HtxContractTradeResult configureLeverage(HtxContractLeverageRequest request) {
        String requestedCode = normalize(request == null ? null : request.getContractCode());
        ReentrantLock lock = contractLocks.computeIfAbsent(lockKey(requestedCode), ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, requestedCode,
                    request == null ? null : request.getLeverage(), "another operation is running for this contract");
        }
        try {
            return executeConfigureLeverage(request);
        } finally {
            lock.unlock();
        }
    }

    private HtxContractTradeResult executeConfigureLeverage(HtxContractLeverageRequest request) {
        String contractCode = normalize(request == null ? null : request.getContractCode());
        Integer leverage = request == null ? null : request.getLeverage();
        HtxContractTradeResult rejected = validateContractAndLeverage(contractCode, leverage);
        if (rejected != null) {
            return rejected;
        }
        if (props.isDryRun()) {
            log.info("[HTX-CONTRACT-DRY-RUN] would switch leverage contract={} leverage={}x", contractCode, leverage);
            return result(HtxContractTradeResult.Code.LEVERAGE_DRY_RUN_OK, contractCode, leverage, "dry-run");
        }
        if (!props.isLiveEnabled()) {
            return result(HtxContractTradeResult.Code.REJECT_LIVE_GATE, contractCode, leverage,
                    "htx.contract.live-enabled=false");
        }
        HtxContractTradeResult leverageCheck = checkAvailableLeverage(contractCode, leverage, "long");
        if (leverageCheck != null) {
            return leverageCheck;
        }
        leverageCheck = checkAvailableLeverage(contractCode, leverage, "short");
        if (leverageCheck != null) {
            return leverageCheck;
        }
        HtxRestResponse<HtxSwapLeverageResult> longResponse =
                swap().switchLeverage(contractCode, "long", leverage);
        HtxRestResponse<HtxSwapLeverageResult> shortResponse =
                swap().switchLeverage(contractCode, "short", leverage);
        if (longResponse == null || !longResponse.ok() || longResponse.getData() == null
                || shortResponse == null || !shortResponse.ok() || shortResponse.getData() == null) {
            return result(HtxContractTradeResult.Code.REJECT_LEVERAGE, contractCode, leverage,
                    "long=" + error(longResponse) + " short=" + error(shortResponse));
        }
        return result(HtxContractTradeResult.Code.LEVERAGE_OK, contractCode, leverage, "ok");
    }

    public HtxContractTradeResult submitLongCycle(HtxContractCycleRequest request) {
        String requestedCode = normalize(request == null ? null : request.getContractCode());
        ReentrantLock lock = contractLocks.computeIfAbsent(lockKey(requestedCode), ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, requestedCode,
                    request == null ? null : request.getLeverage(), "another cycle is running for this contract");
        }
        try {
            return executeLongCycle(request);
        } finally {
            lock.unlock();
        }
    }

    /** Submit equal-price long and short legs, then equal-price reduce-only closes for actual fills. */
    public HtxContractTradeResult submitDualCycle(HtxContractCycleRequest request) {
        String requestedCode = normalize(request == null ? null : request.getContractCode());
        ReentrantLock lock = contractLocks.computeIfAbsent(lockKey(requestedCode), ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, requestedCode,
                    request == null ? null : request.getLeverage(), "another cycle is running for this contract");
        }
        try {
            return executeDualCycle(request);
        } finally {
            lock.unlock();
        }
    }

    /** Advance one persisted maker-grid exit without opening another cycle. */
    public HtxContractTradeResult reconcileMakerGridCycle(String requestedCode) {
        String contractCode = normalize(requestedCode);
        if (props.isDryRun() || !props.isLiveEnabled()) {
            return result(HtxContractTradeResult.Code.REJECT_LIVE_GATE, contractCode, props.getLeverage(),
                    "maker-grid reconciliation requires dry-run=false and live-enabled=true");
        }
        HtxContractTradeResult rejected = validateContractAndLeverage(contractCode, props.getLeverage());
        if (rejected != null) {
            return rejected;
        }
        ReentrantLock lock = contractLocks.computeIfAbsent(lockKey(contractCode), ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, props.getLeverage(),
                    "another operation is running for this contract");
        }
        try {
            return executeMakerGridReconciliation(contractCode);
        } finally {
            lock.unlock();
        }
    }

    private HtxContractTradeResult executeMakerGridReconciliation(String contractCode) {
        Optional<HtxContractCycleEntity> active = recorder.findActiveMakerGridCycle(contractCode);
        if (active.isEmpty()) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, props.getLeverage(),
                    "no active maker-grid cycle");
        }
        HtxContractCycleEntity cycle = active.get();
        if (!HtxContractTradeRecorder.CLOSE_PENDING.equals(cycle.getStatus())
                && !HtxContractTradeRecorder.EXPOSURE.equals(cycle.getStatus())) {
            return gridResult(cycle, HtxContractTradeResult.Code.EXPOSURE,
                    "maker-grid entry requires manual reconciliation before exits can advance");
        }
        List<HtxContractLegEntity> legs = recorder.findLegs(cycle.getId());
        if (!refreshPersistedCloseLegs(cycle.getId(), contractCode, legs)) {
            return gridResult(cycle, HtxContractTradeResult.Code.QUERY_INFRA_ERROR,
                    "maker-grid close query failed; no new order sent");
        }
        legs = recorder.findLegs(cycle.getId());
        GridVolumes volumes = gridVolumes(legs);
        if (volumes.closed()) {
            recorder.update(cycle.getId(), HtxContractTradeRecorder.DONE, cycle.getOpenOrderId(),
                    cycle.getCloseOrderId(), volumes.totalOpen(), volumes.totalClose(),
                    "maker grid flattened at fee-protected prices");
            return gridResult(cycle, HtxContractTradeResult.Code.OK, "maker grid flattened");
        }
        long ageMs = cycle.getUpdatedAt() == null ? Long.MAX_VALUE
                : java.time.Duration.between(cycle.getUpdatedAt(), java.time.LocalDateTime.now()).toMillis();
        if (HtxContractTradeRecorder.CLOSE_PENDING.equals(cycle.getStatus())
                && ageMs < props.getProfitGridExitTtlMs()) {
            return gridResult(cycle, HtxContractTradeResult.Code.CLOSE_PENDING,
                    "maker exits remain inside profit-grid TTL");
        }
        if (!cancelActiveCloseLegs(cycle.getId(), contractCode, legs)) {
            recorder.update(cycle.getId(), HtxContractTradeRecorder.EXPOSURE, cycle.getOpenOrderId(),
                    cycle.getCloseOrderId(), volumes.totalOpen(), volumes.totalClose(),
                    "maker exit cancellation not terminal; manual reconciliation required");
            return gridResult(cycle, HtxContractTradeResult.Code.EXPOSURE,
                    "maker exit cancellation not terminal");
        }

        legs = recorder.findLegs(cycle.getId());
        volumes = gridVolumes(legs);
        if (volumes.closed()) {
            recorder.update(cycle.getId(), HtxContractTradeRecorder.DONE, cycle.getOpenOrderId(),
                    cycle.getCloseOrderId(), volumes.totalOpen(), volumes.totalClose(),
                    "maker grid flattened during cancellation race");
            return gridResult(cycle, HtxContractTradeResult.Code.OK, "maker grid flattened");
        }
        int retry = nextGridRetry(legs);
        if (retry > props.getFastIocCloseRetryAttempts()) {
            recorder.update(cycle.getId(), HtxContractTradeRecorder.EXPOSURE, cycle.getOpenOrderId(),
                    cycle.getCloseOrderId(), volumes.totalOpen(), volumes.totalClose(),
                    "maker-grid emergency close retries exhausted");
            return gridResult(cycle, HtxContractTradeResult.Code.EXPOSURE,
                    "maker-grid emergency close retries exhausted");
        }
        HtxSwapContractInfo contract = loadContract(contractCode);
        if (!contractAvailable(contract)) {
            return gridResult(cycle, HtxContractTradeResult.Code.EXPOSURE, "contract unavailable during exit");
        }
        ExecutablePrices prices = executablePrices(contractCode, volumes.remainingShort(),
                volumes.remainingLong(), contract.getPriceTick());
        if (!prices.available()) {
            return gridResult(cycle, HtxContractTradeResult.Code.EXPOSURE,
                    "emergency close blocked: " + prices.error());
        }
        List<LegExecution> emergency = new ArrayList<>(2);
        long idBase = cycle.getClientOrderId() + 100L + retry * 2L;
        if (volumes.remainingLong() > 0L) {
            emergency.add(leg(HtxContractTradeRecorder.LONG_CLOSE + "_R" + retry, idBase,
                    "sell", "long", true, volumes.remainingLong(), prices.sellPrice(), contractCode,
                    ExecutionMode.IOC));
        }
        if (volumes.remainingShort() > 0L) {
            emergency.add(leg(HtxContractTradeRecorder.SHORT_CLOSE + "_R" + retry, idBase + 1L,
                    "buy", "short", true, volumes.remainingShort(), prices.buyPrice(), contractCode,
                    ExecutionMode.IOC));
        }
        try {
            persistLegIntents(cycle.getId(), emergency);
        } catch (HtxPersistenceException e) {
            return gridResult(cycle, HtxContractTradeResult.Code.EXPOSURE, e.getMessage());
        }
        recorder.update(cycle.getId(), HtxContractTradeRecorder.EXPOSURE, cycle.getOpenOrderId(),
                cycle.getCloseOrderId(), volumes.totalOpen(), volumes.totalClose(),
                "maker-grid emergency reduce-only IOC retry=" + retry);
        sendBatch(cycle.getId(), emergency);
        for (LegExecution execution : emergency) {
            settleCloseLeg(cycle.getId(), contractCode, execution, props.getFillTimeoutMs());
        }
        GridVolumes after = gridVolumes(recorder.findLegs(cycle.getId()));
        if (after.closed()) {
            recorder.update(cycle.getId(), HtxContractTradeRecorder.DONE, cycle.getOpenOrderId(),
                    cycle.getCloseOrderId(), after.totalOpen(), after.totalClose(),
                    "maker grid flattened by emergency reduce-only IOC");
            return gridResult(cycle, HtxContractTradeResult.Code.OK, "maker grid emergency-flattened");
        }
        return gridResult(cycle, HtxContractTradeResult.Code.EXPOSURE,
                "maker-grid emergency close remains incomplete");
    }

    private HtxContractTradeResult executeDualCycle(HtxContractCycleRequest request) {
        String contractCode = normalize(request == null ? null : request.getContractCode());
        int leverage = request != null && request.getLeverage() != null
                ? request.getLeverage() : props.getLeverage();
        boolean fastIoc = request != null && Boolean.TRUE.equals(request.getFastIoc());
        boolean makerGrid = request != null && Boolean.TRUE.equals(request.getMakerGrid());
        HtxContractTradeResult rejected = validateCycle(request, contractCode, leverage);
        if (rejected != null) {
            return rejected;
        }
        if (makerGrid && (!validMakerGridConfiguration()
                || requestedMakerPricesInvalid(request))) {
            return result(HtxContractTradeResult.Code.REJECT_INVALID_INPUT, contractCode, leverage,
                    "maker-grid prices, fee rates, buffers, TTL and retry limits are invalid");
        }
        long retries = Math.max(0L, (long) props.getFastIocCloseRetryAttempts());
        long maxClientOrderOffset = makerGrid ? 101L + retries * 2L
                : 3L + (fastIoc ? retries * 2L : 0L);
        if (request.getClientOrderId() != null
                && request.getClientOrderId() > Long.MAX_VALUE - maxClientOrderOffset) {
            return result(HtxContractTradeResult.Code.REJECT_INVALID_INPUT, contractCode, leverage,
                    "dual cycle client order id range would overflow");
        }

        HtxSwapContractInfo contract = loadContract(contractCode);
        if (!contractAvailable(contract)) {
            return result(HtxContractTradeResult.Code.REJECT_CONTRACT_UNAVAILABLE, contractCode, leverage,
                    "contract unavailable or isolated margin unsupported");
        }
        BigDecimal requestedLongOpen = makerGrid ? request.getLongOpenPrice() : request.getOpenPrice();
        BigDecimal requestedShortOpen = makerGrid ? request.getShortOpenPrice() : request.getOpenPrice();
        if (!aligned(request.getOpenPrice(), contract.getPriceTick())
                || !aligned(request.getClosePrice(), contract.getPriceTick())
                || !aligned(requestedLongOpen, contract.getPriceTick())
                || !aligned(requestedShortOpen, contract.getPriceTick())) {
            return result(HtxContractTradeResult.Code.REJECT_PRICE_TICK, contractCode, leverage,
                    "price must align to tick=" + contract.getPriceTick());
        }

        BigDecimal legNotional = contract.getContractSize()
                .multiply(BigDecimal.valueOf(request.getVolume()))
                .multiply(requestedLongOpen.max(requestedShortOpen));
        if (legNotional.compareTo(props.getMinQuoteNotional()) < 0
                || (props.getMaxSingleQuoteNotional().signum() > 0
                && legNotional.compareTo(props.getMaxSingleQuoteNotional()) > 0)) {
            return result(HtxContractTradeResult.Code.REJECT_NOTIONAL, contractCode, leverage,
                    "per-leg notional=" + legNotional.toPlainString());
        }

        HtxContractTradeResult depthRejection = checkDepth(contractCode, leverage,
                BigDecimal.valueOf(request.getVolume()), BigDecimal.valueOf(request.getVolume()), "open");
        if (depthRejection != null) {
            return depthRejection;
        }
        if (props.isDryRun()) {
            log.info("[HTX-CONTRACT-DRY-RUN] would batch open long+short contract={} volume={} leverage={}x "
                            + "longOpen={} shortOpen={} makerGrid={} fastIoc={}",
                    contractCode, request.getVolume(), leverage, requestedLongOpen, requestedShortOpen,
                    makerGrid, fastIoc);
            return result(HtxContractTradeResult.Code.DRY_RUN_OK, contractCode, leverage,
                    "dual dry-run per-leg notional=" + legNotional.toPlainString());
        }
        if (!props.isLiveEnabled()) {
            return result(HtxContractTradeResult.Code.REJECT_LIVE_GATE, contractCode, leverage,
                    "htx.contract.live-enabled=false");
        }
        if (request.getClientOrderId() == null || request.getClientOrderId() <= 0) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "positive clientOrderId is required for live dual trading");
        }

        HtxContractTradeResult privatePreflight = dualPrivatePreflight(
                contractCode, leverage, legNotional.multiply(BigDecimal.valueOf(2)));
        if (privatePreflight != null) {
            return privatePreflight;
        }

        ExecutablePrices openPrices = fastIoc
                ? executablePrices(contractCode, request.getVolume(), request.getVolume(), contract.getPriceTick())
                : ExecutablePrices.ok(requestedLongOpen, requestedShortOpen);
        if (!openPrices.available()) {
            return result(HtxContractTradeResult.Code.REJECT_DEPTH, contractCode, leverage,
                    "open " + openPrices.error());
        }

        long cycleId;
        try {
            cycleId = recorder.createIntent(request, contractCode, leverage);
        } catch (HtxPersistenceException e) {
            return result(HtxContractTradeResult.Code.REJECT_PERSISTENCE, contractCode, leverage, e.getMessage());
        }

        ExecutionMode openMode = makerGrid ? ExecutionMode.POST_ONLY
                : fastIoc ? ExecutionMode.IOC : ExecutionMode.LIMIT;
        LegExecution longOpen = leg(HtxContractTradeRecorder.LONG_OPEN,
                request.getClientOrderId(), "buy", "long", false,
                request.getVolume(), openPrices.buyPrice(), contractCode, openMode);
        LegExecution shortOpen = leg(HtxContractTradeRecorder.SHORT_OPEN,
                request.getClientOrderId() + 1L, "sell", "short", false,
                request.getVolume(), openPrices.sellPrice(), contractCode, openMode);
        List<LegExecution> openLegs = List.of(longOpen, shortOpen);
        try {
            persistLegIntents(cycleId, openLegs);
        } catch (HtxPersistenceException e) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.REJECT_PERSISTENCE,
                    HtxContractTradeRecorder.INTENT, contractCode, leverage,
                    longOpen, shortOpen, null, null, e.getMessage());
        }

        int openAccepted = sendBatch(cycleId, openLegs);
        if (longOpen.outcome.infraFailure || shortOpen.outcome.infraFailure) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.QUERY_INFRA_ERROR,
                    HtxContractTradeRecorder.OPEN_UNKNOWN, contractCode, leverage,
                    longOpen, shortOpen, null, null,
                    "open batch acknowledgement is unknown; reconcile by client order id");
        }
        if (openAccepted == 0) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.OPEN_ORDER_REJECTED,
                    HtxContractTradeRecorder.OPEN_REJECTED, contractCode, leverage,
                    longOpen, shortOpen, null, null, "both V5 open legs were rejected");
        }
        recorder.update(cycleId, HtxContractTradeRecorder.OPEN_SENT,
                longOpen.orderId, null, BigDecimal.ZERO, null, "dual open batch accepted=" + openAccepted);

        long timeout = request.getWaitFillTimeoutMs() == null
                ? props.getFillTimeoutMs() : request.getWaitFillTimeoutMs();
        settleOpenLeg(cycleId, contractCode, longOpen, timeout);
        settleOpenLeg(cycleId, contractCode, shortOpen, timeout);
        if (longOpen.outcome.infraFailure || shortOpen.outcome.infraFailure) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.QUERY_INFRA_ERROR,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    longOpen, shortOpen, null, null, "open state unknown; no further order sent");
        }

        BigDecimal longVolume = longOpen.outcome.tradeVolume;
        BigDecimal shortVolume = shortOpen.outcome.tradeVolume;
        if (longVolume.signum() <= 0 && shortVolume.signum() <= 0) {
            HtxContractTradeResult.Code code = openAccepted < 2
                    ? HtxContractTradeResult.Code.OPEN_ORDER_REJECTED
                    : HtxContractTradeResult.Code.OPEN_WAIT_TIMEOUT;
            return recordedDualResult(cycleId, code, HtxContractTradeRecorder.OPEN_CANCELLED,
                    contractCode, leverage, longOpen, shortOpen, null, null,
                    "dual open produced no position");
        }

        recorder.update(cycleId, HtxContractTradeRecorder.OPEN_FILLED,
                longOpen.orderId, null, longVolume.add(shortVolume), null,
                "long=" + longVolume + " short=" + shortVolume);
        long longCloseVolume;
        long shortCloseVolume;
        try {
            longCloseVolume = longVolume.longValueExact();
            shortCloseVolume = shortVolume.longValueExact();
        } catch (ArithmeticException e) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    longOpen, shortOpen, null, null, "HTX returned non-integer contract fill");
        }
        boolean symmetricOpen = longVolume.signum() > 0 && longVolume.compareTo(shortVolume) == 0;
        ExecutablePrices closePrices;
        ExecutionMode closeMode;
        if (makerGrid && symmetricOpen) {
            ContractGridStrategy.ExitQuote quote;
            try {
                quote = gridStrategy.exitQuote(
                        positionCost(longOpen, contract, longCloseVolume),
                        positionCost(shortOpen, contract, shortCloseVolume),
                        props.getMakerFeeRatePerSide(), props.getFundingBufferRate(),
                        props.getSafetyBufferRate(), props.getMinNetProfitRate(), contract.getPriceTick());
            } catch (IllegalArgumentException e) {
                return recordedDualResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                        HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                        longOpen, shortOpen, null, null, "maker-grid exit pricing failed: " + e.getMessage());
            }
            closePrices = ExecutablePrices.ok(quote.shortClosePrice(), quote.longClosePrice());
            closeMode = ExecutionMode.POST_ONLY;
        } else if (fastIoc || makerGrid) {
            closePrices = executablePrices(contractCode, shortCloseVolume, longCloseVolume,
                    contract.getPriceTick());
            if (!closePrices.available()) {
                return recordedDualResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                        HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                        longOpen, shortOpen, null, null,
                        "close depth rejected after open: " + closePrices.error());
            }
            closeMode = ExecutionMode.IOC;
        } else {
            depthRejection = checkDepth(contractCode, leverage, shortVolume, longVolume, "close");
            if (depthRejection != null) {
                return recordedDualResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                        HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                        longOpen, shortOpen, null, null,
                        "close depth rejected after open: " + depthRejection.getMessage());
            }
            closePrices = ExecutablePrices.ok(request.getClosePrice(), request.getClosePrice());
            closeMode = ExecutionMode.LIMIT;
        }

        List<LegExecution> closeLegs = new ArrayList<>(2);
        LegExecution longClose = null;
        LegExecution shortClose = null;
        try {
            if (longVolume.signum() > 0) {
                longClose = leg(HtxContractTradeRecorder.LONG_CLOSE,
                        request.getClientOrderId() + 2L, "sell", "long", true,
                        longCloseVolume, closePrices.sellPrice(), contractCode, closeMode);
                closeLegs.add(longClose);
            }
            if (shortVolume.signum() > 0) {
                shortClose = leg(HtxContractTradeRecorder.SHORT_CLOSE,
                        request.getClientOrderId() + 3L, "buy", "short", true,
                        shortCloseVolume, closePrices.buyPrice(), contractCode, closeMode);
                closeLegs.add(shortClose);
            }
            persistLegIntents(cycleId, closeLegs);
        } catch (ArithmeticException | HtxPersistenceException e) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    longOpen, shortOpen, longClose, shortClose,
                    "cannot persist exact reduce-only close legs: " + e.getMessage());
        }

        int closeAccepted = sendBatch(cycleId, closeLegs);
        if (closeLegs.stream().anyMatch(leg -> leg.outcome.infraFailure)) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    longOpen, shortOpen, longClose, shortClose,
                    "close batch acknowledgement is unknown; reconcile by client order id");
        }
        if (closeAccepted == 0 && !fastIoc) {
            return recordedDualResult(cycleId, HtxContractTradeResult.Code.CLOSE_ORDER_REJECTED,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    longOpen, shortOpen, longClose, shortClose, "all close legs were rejected");
        }
        recorder.update(cycleId, HtxContractTradeRecorder.CLOSE_PENDING,
                longOpen.orderId, longClose == null ? null : longClose.orderId,
                longVolume.add(shortVolume), BigDecimal.ZERO,
                "dual reduce-only close batch accepted=" + closeAccepted);
        settleCloseLeg(cycleId, contractCode, longClose, timeout);
        settleCloseLeg(cycleId, contractCode, shortClose, timeout);
        if (fastIoc && (!closed(longOpen, longClose) || !closed(shortOpen, shortClose))) {
            retryFastIocCloses(cycleId, request, contractCode, contract.getPriceTick(), timeout,
                    longOpen, shortOpen, longClose, shortClose);
        }

        boolean longClosed = closed(longOpen, longClose);
        boolean shortClosed = closed(shortOpen, shortClose);
        if (longClosed && shortClosed) {
            return recordedDualResult(cycleId, symmetricOpen
                            ? HtxContractTradeResult.Code.OK : HtxContractTradeResult.Code.OPEN_ORDER_REJECTED,
                    HtxContractTradeRecorder.DONE, contractCode, leverage,
                    longOpen, shortOpen, longClose, shortClose,
                    symmetricOpen ? "dual cycle flattened" : "asymmetric open safely flattened");
        }
        boolean unknownOrTerminal = hasCloseFailure(longClose) || hasCloseFailure(shortClose);
        return recordedDualResult(cycleId, unknownOrTerminal
                        ? HtxContractTradeResult.Code.EXPOSURE : HtxContractTradeResult.Code.CLOSE_PENDING,
                unknownOrTerminal ? HtxContractTradeRecorder.EXPOSURE : HtxContractTradeRecorder.CLOSE_PENDING,
                contractCode, leverage, longOpen, shortOpen, longClose, shortClose,
                unknownOrTerminal ? "one or more close legs failed before flattening" : "close legs remain open");
    }

    private HtxContractTradeResult executeLongCycle(HtxContractCycleRequest request) {
        String contractCode = normalize(request == null ? null : request.getContractCode());
        int leverage = request != null && request.getLeverage() != null
                ? request.getLeverage() : props.getLeverage();
        HtxContractTradeResult rejected = validateCycle(request, contractCode, leverage);
        if (rejected != null) {
            return rejected;
        }

        HtxSwapContractInfo contract = loadContract(contractCode);
        if (contract == null || contract.getContractStatus() == null || contract.getContractStatus() != 1
                || !"swap".equalsIgnoreCase(contract.getBusinessType())
                || !("all".equalsIgnoreCase(contract.getSupportMarginMode())
                || "isolated".equalsIgnoreCase(contract.getSupportMarginMode()))
                || contract.getContractSize() == null || contract.getContractSize().signum() <= 0
                || contract.getPriceTick() == null || contract.getPriceTick().signum() <= 0) {
            return result(HtxContractTradeResult.Code.REJECT_CONTRACT_UNAVAILABLE, contractCode, leverage,
                    "contract unavailable or isolated margin unsupported");
        }
        if (!aligned(request.getOpenPrice(), contract.getPriceTick())
                || !aligned(request.getClosePrice(), contract.getPriceTick())) {
            return result(HtxContractTradeResult.Code.REJECT_PRICE_TICK, contractCode, leverage,
                    "price must align to tick=" + contract.getPriceTick());
        }

        BigDecimal notional = contract.getContractSize()
                .multiply(BigDecimal.valueOf(request.getVolume()))
                .multiply(request.getOpenPrice());
        if (notional.compareTo(props.getMinQuoteNotional()) < 0
                || (props.getMaxSingleQuoteNotional().signum() > 0
                && notional.compareTo(props.getMaxSingleQuoteNotional()) > 0)) {
            return result(HtxContractTradeResult.Code.REJECT_NOTIONAL, contractCode, leverage,
                    "notional=" + notional.toPlainString());
        }

        HtxRestResponse<HtxDepth> depthResponse = swap().depth(contractCode, props.getDepthStep());
        if (!depthResponse.ok() || depthResponse.payload() == null) {
            return result(HtxContractTradeResult.Code.REJECT_DEPTH, contractCode, leverage, error(depthResponse));
        }
        HtxDepthGuard.Verdict depth = depthGuard.check(depthResponse.payload(),
                BigDecimal.valueOf(request.getVolume()), props.getDepthSafetyRatio(), props.getDepthTopN());
        if (!depth.allOk()) {
            return result(HtxContractTradeResult.Code.REJECT_DEPTH, contractCode, leverage,
                    "required=" + depth.getRequiredAmount() + " asks=" + depth.getCumulativeAskAmount()
                            + " bids=" + depth.getCumulativeBidAmount());
        }

        if (props.isDryRun()) {
            log.info("[HTX-CONTRACT-DRY-RUN] would open-long contract={} volume={} leverage={}x price={} then close-long price={}",
                    contractCode, request.getVolume(), leverage, request.getOpenPrice(), request.getClosePrice());
            return result(HtxContractTradeResult.Code.DRY_RUN_OK, contractCode, leverage,
                    "dry-run notional=" + notional.toPlainString());
        }
        if (!props.isLiveEnabled()) {
            return result(HtxContractTradeResult.Code.REJECT_LIVE_GATE, contractCode, leverage,
                    "htx.contract.live-enabled=false");
        }
        if (request.getClientOrderId() == null || request.getClientOrderId() <= 0
                || request.getClientOrderId() == Long.MAX_VALUE) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "positive clientOrderId below Long.MAX_VALUE is required for live trading");
        }
        try {
            if (recorder.hasActiveCycle(contractCode)) {
                return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                        "unfinished contract cycle requires reconciliation");
            }
        } catch (HtxPersistenceException e) {
            return result(HtxContractTradeResult.Code.REJECT_PERSISTENCE, contractCode, leverage, e.getMessage());
        }

        HtxRestResponse<HtxSwapOpenOrders> openOrdersResponse = swap().openOrders(contractCode);
        HtxSwapOpenOrders openOrders = openOrdersResponse == null || !openOrdersResponse.ok()
                ? null : openOrdersResponse.getData();
        if (openOrders == null || openOrders.getOrders() == null) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "open orders query failed: " + error(openOrdersResponse));
        }
        if (!openOrders.getOrders().isEmpty()) {
            return result(HtxContractTradeResult.Code.REJECT_EXISTING_ORDER, contractCode, leverage,
                    "existing contract orders must be reconciled before starting a managed cycle");
        }

        HtxContractTradeResult leverageCheck = checkAvailableLeverage(contractCode, leverage, "long");
        if (leverageCheck != null) {
            return leverageCheck;
        }
        HtxRestResponse<List<HtxSwapPosition>> positionResponse = swap().positionInfo(contractCode);
        if (positionResponse == null || !positionResponse.ok() || positionResponse.getData() == null) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "position query failed: " + error(positionResponse));
        }
        List<HtxSwapPosition> positions = positionResponse.getData();
        boolean hasPosition = positions.stream().anyMatch(p -> p.getVolume() != null && p.getVolume().signum() > 0);
        if (hasPosition) {
            return result(HtxContractTradeResult.Code.REJECT_EXISTING_POSITION, contractCode, leverage,
                    "existing position must be closed before starting a managed cycle");
        }

        HtxSwapAccount account = findAccount(payload(swap().accountInfo(contractCode)), contractCode);
        if (account == null || !"normal".equalsIgnoreCase(account.getState())) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "V5 contract account is missing or not normal");
        }
        BigDecimal requiredMargin = notional.divide(BigDecimal.valueOf(leverage), 18, RoundingMode.UP)
                .multiply(props.getMarginSafetyRatio());
        if (account.getMarginAvailable() == null || account.getMarginAvailable().compareTo(requiredMargin) < 0) {
            return result(HtxContractTradeResult.Code.REJECT_INSUFFICIENT_MARGIN, contractCode, leverage,
                    "available=" + account.getMarginAvailable() + " required=" + requiredMargin);
        }
        if (account.getLeverage() == null || account.getLeverage() != leverage) {
            HtxRestResponse<HtxSwapLeverageResult> switchResponse = swap().switchLeverage(contractCode, leverage);
            if (!switchResponse.ok()) {
                return result(HtxContractTradeResult.Code.REJECT_LEVERAGE, contractCode, leverage,
                        error(switchResponse));
            }
        }

        long cycleId;
        try {
            cycleId = recorder.createIntent(request, contractCode, leverage);
        } catch (HtxPersistenceException e) {
            return result(HtxContractTradeResult.Code.REJECT_PERSISTENCE, contractCode, leverage, e.getMessage());
        }

        HtxRestResponse<HtxSwapOrderResult> openResponse = swap().placeOrder(
                contractCode, request.getClientOrderId(), request.getVolume(), "buy", "open", leverage,
                request.getOpenPrice().toPlainString(), "limit");
        Long openOrderId = orderId(openResponse);
        if (openOrderId == null) {
            return recordedResult(cycleId, HtxContractTradeResult.Code.OPEN_ORDER_REJECTED,
                    HtxContractTradeRecorder.OPEN_REJECTED, contractCode, leverage,
                    null, null, BigDecimal.ZERO, null, error(openResponse));
        }
        recorder.update(cycleId, HtxContractTradeRecorder.OPEN_SENT, openOrderId, null,
                BigDecimal.ZERO, null, "open order accepted");
        long timeout = request.getWaitFillTimeoutMs() == null
                ? props.getFillTimeoutMs() : request.getWaitFillTimeoutMs();
        FillOutcome open = waitForOrder(contractCode, openOrderId, timeout);
        if (open.infraFailure) {
            return recordedResult(cycleId, HtxContractTradeResult.Code.QUERY_INFRA_ERROR,
                    HtxContractTradeRecorder.OPEN_UNKNOWN, contractCode, leverage,
                    openOrderId, null, open.tradeVolume, null, "open query failed; order left untouched");
        }
        if (!open.fullyFilled) {
            HtxRestResponse<java.util.Map<String, Object>> cancel = swap().cancelOrder(contractCode, openOrderId);
            HtxRestResponse<List<HtxSwapOrder>> afterCancel = swap().queryOrder(contractCode, openOrderId);
            if (!cancel.ok() || !afterCancel.ok() || first(afterCancel) == null) {
                return recordedResult(cycleId, HtxContractTradeResult.Code.QUERY_INFRA_ERROR,
                        HtxContractTradeRecorder.OPEN_UNKNOWN, contractCode, leverage,
                        openOrderId, null, open.tradeVolume, null, "open cancel state unknown");
            }
            open = FillOutcome.from(first(afterCancel));
        }
        if (open.tradeVolume.signum() <= 0) {
            return recordedResult(cycleId, open.terminalRejected
                            ? HtxContractTradeResult.Code.OPEN_ORDER_REJECTED
                            : HtxContractTradeResult.Code.OPEN_WAIT_TIMEOUT,
                    open.terminalRejected ? HtxContractTradeRecorder.OPEN_REJECTED
                            : HtxContractTradeRecorder.OPEN_CANCELLED,
                    contractCode, leverage, openOrderId, null, BigDecimal.ZERO, null, "open produced no fill");
        }
        recorder.update(cycleId, HtxContractTradeRecorder.OPEN_FILLED, openOrderId, null,
                open.tradeVolume, null, "open filled");

        long closeVolume;
        try {
            closeVolume = open.tradeVolume.longValueExact();
        } catch (ArithmeticException e) {
            return recordedResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    openOrderId, null, open.tradeVolume, null, "HTX returned non-integer contract fill");
        }
        HtxRestResponse<HtxSwapOrderResult> closeResponse = swap().placeOrder(
                contractCode, request.getClientOrderId() + 1, closeVolume, "sell", "close", leverage,
                request.getClosePrice().toPlainString(), "limit");
        Long closeOrderId = orderId(closeResponse);
        if (closeOrderId == null) {
            return recordedResult(cycleId, HtxContractTradeResult.Code.CLOSE_ORDER_REJECTED,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    openOrderId, null, open.tradeVolume, null, error(closeResponse));
        }
        recorder.update(cycleId, HtxContractTradeRecorder.CLOSE_PENDING, openOrderId, closeOrderId,
                open.tradeVolume, BigDecimal.ZERO, "limit close accepted");
        FillOutcome close = waitForOrder(contractCode, closeOrderId, timeout);
        if (close.infraFailure) {
            return recordedResult(cycleId, HtxContractTradeResult.Code.QUERY_INFRA_ERROR,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    openOrderId, closeOrderId, open.tradeVolume, close.tradeVolume,
                    "close query failed; close order left untouched");
        }
        if (close.fullyFilled && close.tradeVolume.compareTo(open.tradeVolume) >= 0) {
            return recordedResult(cycleId, HtxContractTradeResult.Code.OK, HtxContractTradeRecorder.DONE,
                    contractCode, leverage, openOrderId, closeOrderId,
                    open.tradeVolume, close.tradeVolume, "ok");
        }
        if (close.terminal) {
            return recordedResult(cycleId, HtxContractTradeResult.Code.EXPOSURE,
                    HtxContractTradeRecorder.EXPOSURE, contractCode, leverage,
                    openOrderId, closeOrderId, open.tradeVolume, close.tradeVolume,
                    "close terminal before flattening full position");
        }
        return recordedResult(cycleId, HtxContractTradeResult.Code.CLOSE_PENDING,
                HtxContractTradeRecorder.CLOSE_PENDING, contractCode, leverage,
                openOrderId, closeOrderId, open.tradeVolume, close.tradeVolume,
                "limit close remains open");
    }

    private HtxContractTradeResult validateCycle(HtxContractCycleRequest request, String contractCode, int leverage) {
        HtxContractTradeResult common = validateContractAndLeverage(contractCode, leverage);
        if (common != null) {
            return common;
        }
        if (request == null || request.getVolume() == null || request.getVolume() <= 0
                || request.getOpenPrice() == null || request.getOpenPrice().signum() <= 0
                || request.getClosePrice() == null || request.getClosePrice().signum() <= 0
                || request.getOpenPrice().compareTo(request.getClosePrice()) > 0
                || props.getFillTimeoutMs() <= 0 || props.getFillPollIntervalMs() < 0
                || props.getQueryFailureThreshold() <= 0
                || (request.getWaitFillTimeoutMs() != null
                && (request.getWaitFillTimeoutMs() <= 0
                || request.getWaitFillTimeoutMs() > props.getFillTimeoutMs()))) {
            return result(HtxContractTradeResult.Code.REJECT_INVALID_INPUT, contractCode, leverage,
                    "volume/prices/timeouts are invalid or request timeout exceeds configured maximum");
        }
        return null;
    }

    private HtxContractTradeResult dualPrivatePreflight(
            String contractCode, int leverage, BigDecimal totalNotional) {
        try {
            if (recorder.hasActiveCycle(contractCode)) {
                return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                        "unfinished contract cycle requires reconciliation");
            }
        } catch (HtxPersistenceException e) {
            return result(HtxContractTradeResult.Code.REJECT_PERSISTENCE, contractCode, leverage, e.getMessage());
        }

        HtxRestResponse<HtxSwapOpenOrders> openOrdersResponse = swap().openOrders(contractCode);
        HtxSwapOpenOrders openOrders = openOrdersResponse == null || !openOrdersResponse.ok()
                ? null : openOrdersResponse.getData();
        if (openOrders == null || openOrders.getOrders() == null) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "open orders query failed: " + error(openOrdersResponse));
        }
        if (!openOrders.getOrders().isEmpty()) {
            return result(HtxContractTradeResult.Code.REJECT_EXISTING_ORDER, contractCode, leverage,
                    "existing contract orders must be reconciled before starting a managed cycle");
        }

        HtxRestResponse<List<HtxSwapPosition>> positionResponse = swap().positionInfo(contractCode);
        if (!positionResponse.ok() || positionResponse.getData() == null) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "position query failed: " + error(positionResponse));
        }
        boolean hasPosition = positionResponse.getData().stream()
                .anyMatch(position -> position.getVolume() != null && position.getVolume().signum() > 0);
        if (hasPosition) {
            return result(HtxContractTradeResult.Code.REJECT_EXISTING_POSITION, contractCode, leverage,
                    "existing position must be closed before starting a managed cycle");
        }

        HtxSwapAccount account = findAccount(payload(swap().accountInfo(contractCode)), contractCode);
        if (account == null || !"normal".equalsIgnoreCase(account.getState())) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "V5 contract account is missing or not normal");
        }
        BigDecimal requiredMargin = totalNotional.divide(BigDecimal.valueOf(leverage), 18, RoundingMode.UP)
                .multiply(props.getMarginSafetyRatio());
        if (account.getMarginAvailable() == null || account.getMarginAvailable().compareTo(requiredMargin) < 0) {
            return result(HtxContractTradeResult.Code.REJECT_INSUFFICIENT_MARGIN, contractCode, leverage,
                    "available=" + account.getMarginAvailable() + " required=" + requiredMargin);
        }

        HtxContractTradeResult leverageCheck = checkAvailableLeverage(contractCode, leverage, "long");
        if (leverageCheck != null) {
            return leverageCheck;
        }
        leverageCheck = checkAvailableLeverage(contractCode, leverage, "short");
        if (leverageCheck != null) {
            return leverageCheck;
        }
        HtxRestResponse<HtxSwapLeverageResult> longSwitch =
                swap().switchLeverage(contractCode, "long", leverage);
        HtxRestResponse<HtxSwapLeverageResult> shortSwitch =
                swap().switchLeverage(contractCode, "short", leverage);
        if (longSwitch == null || !longSwitch.ok() || longSwitch.getData() == null
                || shortSwitch == null || !shortSwitch.ok() || shortSwitch.getData() == null) {
            return result(HtxContractTradeResult.Code.REJECT_LEVERAGE, contractCode, leverage,
                    "long=" + error(longSwitch) + " short=" + error(shortSwitch));
        }
        return null;
    }

    private HtxContractTradeResult checkDepth(String contractCode, int leverage,
                                              BigDecimal buyVolume, BigDecimal sellVolume,
                                              String phase) {
        HtxRestResponse<HtxDepth> response = swap().depth(contractCode, props.getDepthStep());
        if (response == null || !response.ok() || response.payload() == null) {
            return result(HtxContractTradeResult.Code.REJECT_DEPTH, contractCode, leverage,
                    phase + " depth query failed: " + error(response));
        }
        HtxDepthGuard.Verdict verdict = depthGuard.checkSides(response.payload(), buyVolume, sellVolume,
                props.getDepthSafetyRatio(), props.getDepthTopN());
        if (!verdict.allOk()) {
            return result(HtxContractTradeResult.Code.REJECT_DEPTH, contractCode, leverage,
                    phase + " depth insufficient required=" + verdict.getRequiredAmount()
                            + " asks=" + verdict.getCumulativeAskAmount()
                            + " bids=" + verdict.getCumulativeBidAmount());
        }
        return null;
    }

    private static boolean contractAvailable(HtxSwapContractInfo contract) {
        return contract != null && Integer.valueOf(1).equals(contract.getContractStatus())
                && "swap".equalsIgnoreCase(contract.getBusinessType())
                && ("all".equalsIgnoreCase(contract.getSupportMarginMode())
                || "isolated".equalsIgnoreCase(contract.getSupportMarginMode()))
                && contract.getContractSize() != null && contract.getContractSize().signum() > 0
                && contract.getPriceTick() != null && contract.getPriceTick().signum() > 0;
    }

    private ExecutablePrices executablePrices(String contractCode, long buyVolume, long sellVolume,
                                               BigDecimal tick) {
        HtxRestResponse<HtxDepth> response = swap().depth(contractCode, props.getDepthStep());
        if (response == null || !response.ok() || response.payload() == null) {
            return ExecutablePrices.error("depth query failed: " + error(response));
        }
        BigDecimal buyQty = BigDecimal.valueOf(buyVolume);
        BigDecimal sellQty = BigDecimal.valueOf(sellVolume);
        HtxDepthGuard.Verdict verdict = depthGuard.checkSides(response.payload(), buyQty, sellQty,
                props.getDepthSafetyRatio(), props.getDepthTopN());
        if (!verdict.allOk()) {
            return ExecutablePrices.error("depth insufficient required=" + verdict.getRequiredAmount()
                    + " asks=" + verdict.getCumulativeAskAmount()
                    + " bids=" + verdict.getCumulativeBidAmount());
        }
        BigDecimal buyPrice = executableLimit(response.payload().getAsks(), buyQty, tick, true);
        BigDecimal sellPrice = executableLimit(response.payload().getBids(), sellQty, tick, false);
        if ((buyVolume > 0 && buyPrice == null) || (sellVolume > 0 && sellPrice == null)) {
            return ExecutablePrices.error("top-of-book levels cannot support IOC safety quantity");
        }
        return ExecutablePrices.ok(buyPrice, sellPrice);
    }

    private BigDecimal executableLimit(List<HtxPriceLevel> levels, BigDecimal volume,
                                       BigDecimal tick, boolean buy) {
        if (volume.signum() == 0) {
            return null;
        }
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal bestPrice = levels == null || levels.isEmpty() ? null : levels.get(0).getPrice();
        int limit = Math.min(props.getDepthTopN(), levels == null ? 0 : levels.size());
        for (int i = 0; i < limit; i++) {
            HtxPriceLevel level = levels.get(i);
            if (level.getAmount() != null) {
                cumulative = cumulative.add(level.getAmount());
            }
            if (cumulative.compareTo(volume) >= 0 && level.getPrice() != null && bestPrice != null) {
                BigDecimal maxSlippage = props.getMaxEmergencySlippageRate();
                BigDecimal boundary = buy
                        ? bestPrice.multiply(BigDecimal.ONE.add(maxSlippage))
                        : bestPrice.multiply(BigDecimal.ONE.subtract(maxSlippage));
                if ((buy && level.getPrice().compareTo(boundary) > 0)
                        || (!buy && level.getPrice().compareTo(boundary) < 0)) {
                    return null;
                }
                RoundingMode rounding = buy ? RoundingMode.CEILING : RoundingMode.FLOOR;
                return level.getPrice().divide(tick, 0, rounding).multiply(tick);
            }
        }
        return null;
    }

    private LegExecution leg(String legType, long clientOrderId, String side, String positionSide,
                             boolean reduceOnly, long volume, BigDecimal price, String contractCode,
                             ExecutionMode mode) {
        String type = ExecutionMode.POST_ONLY.equals(mode) ? "post_only" : "limit";
        HtxSwapOrderRequest instruction = HtxUsdtSwapService.orderRequest(
                contractCode, clientOrderId, volume, side, positionSide, reduceOnly,
                price.toPlainString(), type);
        if (ExecutionMode.IOC.equals(mode)) {
            instruction.setTimeInForce("ioc");
        }
        return new LegExecution(legType, clientOrderId, instruction, volume, price);
    }

    private void persistLegIntents(long cycleId, List<LegExecution> legs) {
        for (LegExecution leg : legs) {
            recorder.createLegIntent(cycleId, leg.legType, leg.clientOrderId,
                    leg.instruction.getSide(), leg.instruction.getPositionSide(),
                    Boolean.TRUE.equals(leg.instruction.getReduceOnly()), leg.requestedVolume, leg.price);
        }
    }

    private int sendBatch(long cycleId, List<LegExecution> legs) {
        HtxRestResponse<List<HtxSwapOrderResult>> response = swap().batchOrders(
                legs.stream().map(leg -> leg.instruction).toList());
        if (response == null || !response.ok() || response.getData() == null) {
            for (LegExecution leg : legs) {
                leg.outcome = FillOutcome.unknown();
                recorder.updateLeg(cycleId, leg.clientOrderId, null, "UNKNOWN",
                        BigDecimal.ZERO, null, BigDecimal.ZERO, null, BigDecimal.ZERO, error(response));
            }
            return 0;
        }

        Map<Long, HtxSwapOrderResult> byClientId = new LinkedHashMap<>();
        for (HtxSwapOrderResult item : response.getData()) {
            if (item.getClientOrderId() != null) {
                byClientId.put(item.getClientOrderId(), item);
            }
        }
        int accepted = 0;
        for (int i = 0; i < legs.size(); i++) {
            LegExecution leg = legs.get(i);
            HtxSwapOrderResult item = byClientId.get(leg.clientOrderId);
            if (item == null && i < response.getData().size()) {
                item = response.getData().get(i);
            }
            boolean itemAccepted = item != null && item.getOrderId() != null
                    && (item.getCode() == null || Integer.valueOf(200).equals(item.getCode()));
            if (itemAccepted) {
                leg.orderId = item.getOrderId();
                accepted++;
                recorder.updateLeg(cycleId, leg.clientOrderId, leg.orderId, "SENT",
                        BigDecimal.ZERO, null, BigDecimal.ZERO, null, BigDecimal.ZERO, "batch accepted");
            } else {
                leg.outcome = FillOutcome.rejected();
                recorder.updateLeg(cycleId, leg.clientOrderId, null, "REJECTED",
                        BigDecimal.ZERO, null, BigDecimal.ZERO, null, BigDecimal.ZERO,
                        item == null ? "missing batch result" : item.getMessage());
            }
        }
        return accepted;
    }

    private void retryFastIocCloses(long cycleId, HtxContractCycleRequest request,
                                    String contractCode, BigDecimal priceTick, long timeout,
                                    LegExecution longOpen, LegExecution shortOpen,
                                    LegExecution longClose, LegExecution shortClose) {
        int maxAttempts = Math.max(0, props.getFastIocCloseRetryAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long remainingLong = remainingContracts(longOpen, longClose);
            long remainingShort = remainingContracts(shortOpen, shortClose);
            if (remainingLong < 0L || remainingShort < 0L) {
                log.error("[HTX-CONTRACT] close retry has non-integer remaining volume cycleId={}", cycleId);
                return;
            }
            if (remainingLong == 0L && remainingShort == 0L) {
                return;
            }

            ExecutablePrices prices = executablePrices(
                    contractCode, remainingShort, remainingLong, priceTick);
            if (!prices.available()) {
                log.error("[HTX-CONTRACT] close retry depth unavailable cycleId={} attempt={} error={}",
                        cycleId, attempt, prices.error());
                return;
            }

            List<LegExecution> retries = new ArrayList<>(2);
            LegExecution longRetry = null;
            LegExecution shortRetry = null;
            if (remainingLong > 0L) {
                longRetry = leg(HtxContractTradeRecorder.LONG_CLOSE + "_R" + attempt,
                        request.getClientOrderId() + 2L + attempt * 2L,
                        "sell", "long", true, remainingLong, prices.sellPrice(), contractCode, ExecutionMode.IOC);
                retries.add(longRetry);
            }
            if (remainingShort > 0L) {
                shortRetry = leg(HtxContractTradeRecorder.SHORT_CLOSE + "_R" + attempt,
                        request.getClientOrderId() + 3L + attempt * 2L,
                        "buy", "short", true, remainingShort, prices.buyPrice(), contractCode, ExecutionMode.IOC);
                retries.add(shortRetry);
            }

            try {
                persistLegIntents(cycleId, retries);
            } catch (HtxPersistenceException e) {
                log.error("[HTX-CONTRACT] close retry persistence failed cycleId={} attempt={}",
                        cycleId, attempt, e);
                return;
            }
            sendBatch(cycleId, retries);
            if (retries.stream().anyMatch(leg -> leg.outcome.infraFailure)) {
                log.error("[HTX-CONTRACT] close retry acknowledgement unknown cycleId={} attempt={}",
                        cycleId, attempt);
                return;
            }
            settleCloseLeg(cycleId, contractCode, longRetry, timeout);
            settleCloseLeg(cycleId, contractCode, shortRetry, timeout);
            mergeCloseOutcome(longClose, longRetry, filled(longOpen));
            mergeCloseOutcome(shortClose, shortRetry, filled(shortOpen));
            recorder.update(cycleId, HtxContractTradeRecorder.CLOSE_PENDING,
                    orderId(longOpen), orderId(longClose),
                    filled(longOpen).add(filled(shortOpen)),
                    filled(longClose).add(filled(shortClose)),
                    "reduce-only IOC close retry attempt=" + attempt);
        }
    }

    private static long remainingContracts(LegExecution open, LegExecution close) {
        BigDecimal remaining = filled(open).subtract(filled(close)).max(BigDecimal.ZERO);
        try {
            return remaining.longValueExact();
        } catch (ArithmeticException e) {
            return -1L;
        }
    }

    private static void mergeCloseOutcome(LegExecution summary, LegExecution retry, BigDecimal targetVolume) {
        if (summary == null || retry == null || retry.outcome == null) {
            return;
        }
        FillOutcome previous = summary.outcome == null ? new FillOutcome() : summary.outcome;
        FillOutcome current = retry.outcome;
        BigDecimal previousVolume = previous.tradeVolume;
        BigDecimal totalVolume = previousVolume.add(current.tradeVolume);
        FillOutcome merged = new FillOutcome();
        merged.tradeVolume = totalVolume;
        merged.averagePrice = weightedAverage(previous.averagePrice, previousVolume,
                current.averagePrice, current.tradeVolume);
        merged.fee = previous.fee.add(current.fee);
        merged.feeCurrency = current.feeCurrency == null ? previous.feeCurrency : current.feeCurrency;
        merged.realizedProfit = previous.realizedProfit.add(current.realizedProfit);
        merged.fullyFilled = totalVolume.compareTo(targetVolume) >= 0;
        merged.terminal = current.terminal;
        merged.terminalRejected = current.terminalRejected;
        merged.infraFailure = current.infraFailure;
        merged.state = merged.fullyFilled ? "filled" : current.state;
        summary.orderId = retry.orderId == null ? summary.orderId : retry.orderId;
        summary.outcome = merged;
    }

    private static BigDecimal weightedAverage(BigDecimal leftPrice, BigDecimal leftVolume,
                                              BigDecimal rightPrice, BigDecimal rightVolume) {
        BigDecimal total = leftVolume.add(rightVolume);
        if (total.signum() == 0) {
            return null;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        if (leftPrice != null) {
            weighted = weighted.add(leftPrice.multiply(leftVolume));
        }
        if (rightPrice != null) {
            weighted = weighted.add(rightPrice.multiply(rightVolume));
        }
        return weighted.divide(total, 18, RoundingMode.HALF_UP);
    }

    private void settleOpenLeg(long cycleId, String contractCode, LegExecution leg, long timeout) {
        if (leg.orderId == null) {
            return;
        }
        FillOutcome outcome = waitForOrder(contractCode, leg.orderId, timeout);
        if (!outcome.infraFailure && !outcome.fullyFilled) {
            swap().cancelOrder(contractCode, leg.orderId);
            HtxRestResponse<List<HtxSwapOrder>> afterCancel = swap().queryOrder(contractCode, leg.orderId);
            HtxSwapOrder order = first(afterCancel);
            if (afterCancel == null || !afterCancel.ok() || order == null) {
                outcome.infraFailure = true;
                outcome.state = "UNKNOWN";
            } else {
                outcome = FillOutcome.from(order);
                if (!outcome.fullyFilled && !outcome.terminal) {
                    outcome.infraFailure = true;
                    outcome.state = "UNKNOWN";
                }
            }
        }
        leg.outcome = outcome;
        updateLeg(cycleId, leg, outcome.infraFailure ? "UNKNOWN" : outcome.state);
    }

    private void settleCloseLeg(long cycleId, String contractCode, LegExecution leg, long timeout) {
        if (leg == null || leg.orderId == null) {
            return;
        }
        FillOutcome outcome = waitForOrder(contractCode, leg.orderId, timeout);
        leg.outcome = outcome;
        updateLeg(cycleId, leg, outcome.infraFailure ? "UNKNOWN" : outcome.state);
    }

    private void updateLeg(long cycleId, LegExecution leg, String state) {
        FillOutcome outcome = leg.outcome;
        recorder.updateLeg(cycleId, leg.clientOrderId, leg.orderId, state,
                outcome.tradeVolume, outcome.averagePrice, outcome.fee,
                outcome.feeCurrency, outcome.realizedProfit, state);
    }

    private static boolean closed(LegExecution open, LegExecution close) {
        if (open == null || open.outcome.tradeVolume.signum() <= 0) {
            return true;
        }
        return close != null && close.outcome.fullyFilled
                && close.outcome.tradeVolume.compareTo(open.outcome.tradeVolume) >= 0;
    }

    private static boolean hasCloseFailure(LegExecution close) {
        return close != null && (close.outcome.infraFailure
                || (close.outcome.terminal && !close.outcome.fullyFilled));
    }

    private HtxContractTradeResult recordedDualResult(
            long cycleId, HtxContractTradeResult.Code code, String status,
            String contractCode, Integer leverage, LegExecution longOpen, LegExecution shortOpen,
            LegExecution longClose, LegExecution shortClose, String message) {
        BigDecimal openFilled = filled(longOpen).add(filled(shortOpen));
        BigDecimal closeFilled = filled(longClose).add(filled(shortClose));
        recorder.update(cycleId, status, orderId(longOpen), orderId(longClose),
                openFilled, closeFilled, message);
        return HtxContractTradeResult.builder()
                .code(code)
                .contractCode(contractCode)
                .leverage(leverage)
                .cycleId(cycleId)
                .openOrderId(orderId(longOpen))
                .closeOrderId(orderId(longClose))
                .longOpenOrderId(orderId(longOpen))
                .shortOpenOrderId(orderId(shortOpen))
                .longCloseOrderId(orderId(longClose))
                .shortCloseOrderId(orderId(shortClose))
                .openFilledVolume(openFilled)
                .closeFilledVolume(closeFilled)
                .longOpenFilledVolume(filled(longOpen))
                .shortOpenFilledVolume(filled(shortOpen))
                .longCloseFilledVolume(filled(longClose))
                .shortCloseFilledVolume(filled(shortClose))
                .totalFee(fee(longOpen).add(fee(shortOpen)).add(fee(longClose)).add(fee(shortClose)))
                .realizedProfit(profit(longOpen).add(profit(shortOpen))
                        .add(profit(longClose)).add(profit(shortClose)))
                .message(message)
                .build();
    }

    private static Long orderId(LegExecution leg) {
        return leg == null ? null : leg.orderId;
    }

    private static BigDecimal filled(LegExecution leg) {
        return leg == null || leg.outcome == null ? BigDecimal.ZERO : leg.outcome.tradeVolume;
    }

    private static BigDecimal fee(LegExecution leg) {
        return leg == null || leg.outcome == null ? BigDecimal.ZERO : leg.outcome.fee;
    }

    private static BigDecimal profit(LegExecution leg) {
        return leg == null || leg.outcome == null ? BigDecimal.ZERO : leg.outcome.realizedProfit;
    }

    private static ContractGridStrategy.PositionCost positionCost(
            LegExecution open, HtxSwapContractInfo contract, long volume) {
        BigDecimal averagePrice = open.outcome.averagePrice == null ? open.price : open.outcome.averagePrice;
        return new ContractGridStrategy.PositionCost(
                averagePrice, contract.getContractSize(), volume, open.outcome.fee);
    }

    private boolean refreshPersistedCloseLegs(long cycleId, String contractCode,
                                              List<HtxContractLegEntity> legs) {
        for (HtxContractLegEntity leg : legs) {
            if (!isCloseLeg(leg) || leg.getOrderId() == null) {
                continue;
            }
            HtxRestResponse<List<HtxSwapOrder>> response = swap().queryOrder(contractCode, leg.getOrderId());
            if (response == null || !response.ok() || response.getData() == null
                    || response.getData().isEmpty()) {
                return false;
            }
            FillOutcome outcome = FillOutcome.from(response.getData().get(0));
            recorder.updateLeg(cycleId, leg.getClientOrderId(), leg.getOrderId(), outcome.state,
                    outcome.tradeVolume, outcome.averagePrice, outcome.fee,
                    outcome.feeCurrency, outcome.realizedProfit, outcome.state);
        }
        return true;
    }

    private boolean cancelActiveCloseLegs(long cycleId, String contractCode,
                                          List<HtxContractLegEntity> legs) {
        for (HtxContractLegEntity leg : legs) {
            if (!isCloseLeg(leg) || leg.getOrderId() == null || terminalState(leg.getState())) {
                continue;
            }
            HtxRestResponse<Map<String, Object>> cancel = swap().cancelOrder(contractCode, leg.getOrderId());
            if (cancel == null || !cancel.ok()) {
                return false;
            }
        }
        return refreshPersistedCloseLegs(cycleId, contractCode, legs)
                && recorder.findLegs(cycleId).stream()
                .filter(HtxContractTradingService::isCloseLeg)
                .allMatch(leg -> leg.getOrderId() == null || terminalState(leg.getState()));
    }

    private static GridVolumes gridVolumes(List<HtxContractLegEntity> legs) {
        BigDecimal longOpen = sumFilled(legs, HtxContractTradeRecorder.LONG_OPEN, false);
        BigDecimal shortOpen = sumFilled(legs, HtxContractTradeRecorder.SHORT_OPEN, false);
        BigDecimal longClose = sumFilled(legs, HtxContractTradeRecorder.LONG_CLOSE, true);
        BigDecimal shortClose = sumFilled(legs, HtxContractTradeRecorder.SHORT_CLOSE, true);
        return new GridVolumes(longOpen, shortOpen, longClose, shortClose);
    }

    private static BigDecimal sumFilled(List<HtxContractLegEntity> legs, String type, boolean prefix) {
        return legs.stream()
                .filter(leg -> prefix ? leg.getLegType().startsWith(type) : type.equals(leg.getLegType()))
                .map(HtxContractLegEntity::getFilledVolume)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int nextGridRetry(List<HtxContractLegEntity> legs) {
        return legs.stream()
                .map(HtxContractLegEntity::getLegType)
                .filter(java.util.Objects::nonNull)
                .mapToInt(HtxContractTradingService::retrySuffix)
                .max().orElse(0) + 1;
    }

    private static int retrySuffix(String legType) {
        int marker = legType.lastIndexOf("_R");
        if (marker < 0 || marker + 2 >= legType.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(legType.substring(marker + 2));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean isCloseLeg(HtxContractLegEntity leg) {
        return leg.getLegType() != null && (leg.getLegType().startsWith(HtxContractTradeRecorder.LONG_CLOSE)
                || leg.getLegType().startsWith(HtxContractTradeRecorder.SHORT_CLOSE));
    }

    private static boolean terminalState(String state) {
        return "filled".equalsIgnoreCase(state) || "partially_canceled".equalsIgnoreCase(state)
                || "canceled".equalsIgnoreCase(state) || "rejected".equalsIgnoreCase(state);
    }

    private HtxContractTradeResult gridResult(HtxContractCycleEntity cycle,
                                              HtxContractTradeResult.Code code,
                                              String message) {
        return HtxContractTradeResult.builder()
                .code(code)
                .contractCode(cycle.getContractCode())
                .leverage(cycle.getLeverage())
                .cycleId(cycle.getId())
                .message(message)
                .build();
    }

    private HtxContractTradeResult validateContractAndLeverage(String contractCode, Integer leverage) {
        if (contractCode == null || leverage == null || leverage <= 0) {
            return result(HtxContractTradeResult.Code.REJECT_INVALID_INPUT, contractCode, leverage,
                    "contractCode and positive leverage are required");
        }
        boolean allowed = props.getContractCodes().stream()
                .map(HtxContractTradingService::normalize)
                .anyMatch(contractCode::equals);
        if (!allowed) {
            return result(HtxContractTradeResult.Code.REJECT_CONTRACT_NOT_ALLOWED, contractCode, leverage,
                    "contract not in whitelist");
        }
        if (leverage > props.getMaxLeverage()) {
            return result(HtxContractTradeResult.Code.REJECT_LEVERAGE, contractCode, leverage,
                    "leverage exceeds configured max=" + props.getMaxLeverage());
        }
        return null;
    }

    private boolean validMakerGridConfiguration() {
        return validRate(props.getMakerFeeRatePerSide())
                && validRate(props.getMinNetProfitRate())
                && validRate(props.getFundingBufferRate())
                && validRate(props.getSafetyBufferRate())
                && validRate(props.getMaxEntrySpreadRate())
                && validRate(props.getMaxEmergencySlippageRate())
                && props.getProfitGridExitTtlMs() > 0L
                && props.getFastIocCloseRetryAttempts() >= 0;
    }

    private static boolean requestedMakerPricesInvalid(HtxContractCycleRequest request) {
        return request.getLongOpenPrice() == null || request.getLongOpenPrice().signum() <= 0
                || request.getShortOpenPrice() == null || request.getShortOpenPrice().signum() <= 0
                || request.getLongOpenPrice().compareTo(request.getShortOpenPrice()) >= 0
                || Boolean.TRUE.equals(request.getFastIoc());
    }

    private static boolean validRate(BigDecimal rate) {
        return rate != null && rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) < 0;
    }

    private HtxContractTradeResult checkAvailableLeverage(
            String contractCode, int leverage, String positionSide) {
        HtxRestResponse<List<HtxSwapAvailableLeverage>> response = "long".equalsIgnoreCase(positionSide)
                ? swap().availableLeverage(contractCode)
                : swap().availableLeverage(contractCode, positionSide);
        HtxSwapAvailableLeverage available = response != null && response.ok()
                ? payload(response).stream()
                .filter(v -> contractCode.equalsIgnoreCase(v.getContractCode())
                        && (v.getPositionSide() == null || positionSide.equalsIgnoreCase(v.getPositionSide())))
                .findFirst().orElse(null)
                : null;
        if (available == null || !containsLeverage(available.getAvailableLevelRate(), leverage)) {
            return result(HtxContractTradeResult.Code.REJECT_LEVERAGE, contractCode, leverage,
                    response != null && response.ok() ? positionSide + " leverage not available: "
                            + (available == null ? null : available.getAvailableLevelRate())
                            : error(response));
        }
        return null;
    }

    private FillOutcome waitForOrder(String contractCode, long orderId, long timeoutMs) {
        long start = System.currentTimeMillis();
        int failures = 0;
        FillOutcome latest = new FillOutcome();
        while (System.currentTimeMillis() - start < timeoutMs) {
            HtxRestResponse<List<HtxSwapOrder>> response = swap().queryOrder(contractCode, orderId);
            HtxSwapOrder order = first(response);
            if (response.ok() && order != null) {
                failures = 0;
                latest = FillOutcome.from(order);
                if (latest.fullyFilled || latest.terminal) {
                    return latest;
                }
            } else if (++failures >= props.getQueryFailureThreshold()) {
                latest.infraFailure = true;
                return latest;
            }
            sleep(props.getFillPollIntervalMs());
        }
        return latest;
    }

    private HtxSwapContractInfo loadContract(String contractCode) {
        HtxRestResponse<List<HtxSwapContractInfo>> response = swap().contractInfo(contractCode);
        if (!response.ok()) {
            return null;
        }
        return payload(response).stream()
                .filter(c -> contractCode.equalsIgnoreCase(c.getContractCode()))
                .findFirst().orElse(null);
    }

    private HtxUsdtSwapService swap() {
        return api.getSwapService();
    }

    private static HtxSwapAccount findAccount(List<HtxSwapAccount> accounts, String contractCode) {
        return accounts.stream().filter(a -> contractCode.equalsIgnoreCase(a.getContractCode()))
                .findFirst().orElse(null);
    }

    private static boolean aligned(BigDecimal price, BigDecimal tick) {
        return price != null && tick != null && tick.signum() > 0 && price.remainder(tick).signum() == 0;
    }

    private static boolean containsLeverage(String csv, int leverage) {
        if (csv == null) {
            return false;
        }
        for (String value : csv.split(",")) {
            if (Integer.toString(leverage).equals(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private static Long orderId(HtxRestResponse<HtxSwapOrderResult> response) {
        return response.ok() && response.getData() != null ? response.getData().getOrderId() : null;
    }

    private static HtxSwapOrder first(HtxRestResponse<List<HtxSwapOrder>> response) {
        List<HtxSwapOrder> data = response == null ? null : response.getData();
        return data == null || data.isEmpty() ? null : data.get(0);
    }

    private static <T> List<T> payload(HtxRestResponse<List<T>> response) {
        return response != null && response.ok() && response.getData() != null
                ? response.getData() : Collections.emptyList();
    }

    private static String normalize(String contractCode) {
        return contractCode == null || contractCode.trim().isEmpty()
                ? null : contractCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String lockKey(String contractCode) {
        return contractCode == null ? "_INVALID_" : contractCode;
    }

    private static String error(HtxRestResponse<?> response) {
        if (response == null) {
            return "null response";
        }
        return String.valueOf(response.getErrCode()) + ":" + String.valueOf(response.getErrMsg());
    }

    private static HtxContractTradeResult result(HtxContractTradeResult.Code code, String contractCode,
                                                  Integer leverage, String message) {
        return HtxContractTradeResult.builder().code(code).contractCode(contractCode)
                .leverage(leverage).message(message).build();
    }

    private static HtxContractTradeResult tradeResult(HtxContractTradeResult.Code code, String contractCode,
                                                       Integer leverage, Long openOrderId, Long closeOrderId,
                                                       BigDecimal openFilled, BigDecimal closeFilled, String message) {
        return HtxContractTradeResult.builder().code(code).contractCode(contractCode).leverage(leverage)
                .openOrderId(openOrderId).closeOrderId(closeOrderId)
                .openFilledVolume(openFilled).closeFilledVolume(closeFilled).message(message).build();
    }

    private HtxContractTradeResult recordedResult(long cycleId, HtxContractTradeResult.Code code, String status,
                                                   String contractCode, Integer leverage,
                                                   Long openOrderId, Long closeOrderId,
                                                   BigDecimal openFilled, BigDecimal closeFilled, String message) {
        recorder.update(cycleId, status, openOrderId, closeOrderId, openFilled, closeFilled, message);
        HtxContractTradeResult result = tradeResult(code, contractCode, leverage, openOrderId, closeOrderId,
                openFilled, closeFilled, message);
        result.setCycleId(cycleId);
        return result;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum ExecutionMode {
        LIMIT,
        IOC,
        POST_ONLY
    }

    private record GridVolumes(BigDecimal longOpen,
                               BigDecimal shortOpen,
                               BigDecimal longClose,
                               BigDecimal shortClose) {
        private boolean closed() {
            return totalOpen().signum() > 0
                    && (longOpen.signum() == 0 || longClose.compareTo(longOpen) >= 0)
                    && (shortOpen.signum() == 0 || shortClose.compareTo(shortOpen) >= 0);
        }

        private BigDecimal totalOpen() {
            return longOpen.add(shortOpen);
        }

        private BigDecimal totalClose() {
            return longClose.add(shortClose);
        }

        private long remainingLong() {
            return positiveRemaining(longOpen, longClose);
        }

        private long remainingShort() {
            return positiveRemaining(shortOpen, shortClose);
        }

        private static long positiveRemaining(BigDecimal opened, BigDecimal closed) {
            return opened.subtract(closed).max(BigDecimal.ZERO).longValueExact();
        }
    }

    private static final class LegExecution {
        private final String legType;
        private final long clientOrderId;
        private final HtxSwapOrderRequest instruction;
        private final long requestedVolume;
        private final BigDecimal price;
        private Long orderId;
        private FillOutcome outcome = new FillOutcome();

        private LegExecution(String legType, long clientOrderId, HtxSwapOrderRequest instruction,
                             long requestedVolume, BigDecimal price) {
            this.legType = legType;
            this.clientOrderId = clientOrderId;
            this.instruction = instruction;
            this.requestedVolume = requestedVolume;
            this.price = price;
        }
    }

    private record ExecutablePrices(BigDecimal buyPrice, BigDecimal sellPrice, String error) {
        private static ExecutablePrices ok(BigDecimal buyPrice, BigDecimal sellPrice) {
            return new ExecutablePrices(buyPrice, sellPrice, null);
        }

        private static ExecutablePrices error(String message) {
            return new ExecutablePrices(null, null, message);
        }

        private boolean available() {
            return error == null;
        }
    }

    private static final class FillOutcome {
        private boolean fullyFilled;
        private boolean terminal;
        private boolean terminalRejected;
        private boolean infraFailure;
        private BigDecimal tradeVolume = BigDecimal.ZERO;
        private BigDecimal averagePrice;
        private BigDecimal fee = BigDecimal.ZERO;
        private String feeCurrency;
        private BigDecimal realizedProfit = BigDecimal.ZERO;
        private String state = "UNKNOWN";

        private static FillOutcome from(HtxSwapOrder order) {
            FillOutcome outcome = new FillOutcome();
            outcome.tradeVolume = order.getTradeVolume() == null ? BigDecimal.ZERO : order.getTradeVolume();
            outcome.averagePrice = order.getTradeAveragePrice();
            outcome.fee = order.getFee() == null ? BigDecimal.ZERO : order.getFee();
            outcome.feeCurrency = order.getFeeCurrency();
            BigDecimal profit = order.getRealProfit() != null ? order.getRealProfit() : order.getProfit();
            outcome.realizedProfit = profit == null ? BigDecimal.ZERO : profit;
            outcome.state = order.getState() == null ? legacyState(order.getStatus()) : order.getState();
            outcome.fullyFilled = "filled".equalsIgnoreCase(outcome.state)
                    || Integer.valueOf(6).equals(order.getStatus());
            outcome.terminal = "partially_canceled".equalsIgnoreCase(outcome.state)
                    || "canceled".equalsIgnoreCase(outcome.state)
                    || "rejected".equalsIgnoreCase(outcome.state)
                    || Integer.valueOf(5).equals(order.getStatus())
                    || Integer.valueOf(7).equals(order.getStatus());
            outcome.terminalRejected = outcome.terminal && outcome.tradeVolume.signum() == 0;
            return outcome;
        }

        private static FillOutcome rejected() {
            FillOutcome outcome = new FillOutcome();
            outcome.terminal = true;
            outcome.terminalRejected = true;
            outcome.state = "rejected";
            return outcome;
        }

        private static FillOutcome unknown() {
            FillOutcome outcome = new FillOutcome();
            outcome.infraFailure = true;
            outcome.state = "UNKNOWN";
            return outcome;
        }

        private static String legacyState(Integer status) {
            if (Integer.valueOf(6).equals(status)) {
                return "filled";
            }
            if (Integer.valueOf(5).equals(status)) {
                return "canceled";
            }
            if (Integer.valueOf(7).equals(status)) {
                return "rejected";
            }
            if (Integer.valueOf(4).equals(status)) {
                return "partially_filled";
            }
            return "new";
        }
    }
}
