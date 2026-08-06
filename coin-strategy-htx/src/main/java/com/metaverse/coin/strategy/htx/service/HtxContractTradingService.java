package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAvailableLeverage;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxContractTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Guarded isolated USDT-margined perpetual execution.
 *
 * <p>V1 deliberately supports long-only, dual-side position mode. It refuses to open when any
 * existing position exists for the contract, so a close order can never consume an unrelated
 * manual position.</p>
 */
@Slf4j
@Service
@ConditionalOnBean(HtxUsdtSwapApiService.class)
@RequiredArgsConstructor
public class HtxContractTradingService {

    private final HtxUsdtSwapApiService api;
    private final HtxDepthGuard depthGuard;
    private final HtxContractProperties props;
    private final HtxContractTradeRecorder recorder;
    private final Map<String, ReentrantLock> contractLocks = new ConcurrentHashMap<>();

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
        HtxContractTradeResult leverageCheck = checkAvailableLeverage(contractCode, leverage);
        if (leverageCheck != null) {
            return leverageCheck;
        }
        HtxRestResponse<HtxSwapLeverageResult> response = swap().switchLeverage(contractCode, leverage);
        if (!response.ok() || response.getData() == null) {
            return result(HtxContractTradeResult.Code.REJECT_LEVERAGE, contractCode, leverage,
                    error(response));
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

        HtxContractTradeResult leverageCheck = checkAvailableLeverage(contractCode, leverage);
        if (leverageCheck != null) {
            return leverageCheck;
        }
        HtxRestResponse<List<HtxSwapPosition>> positionResponse = swap().positionInfo(contractCode);
        if (!positionResponse.ok() || positionResponse.getData() == null) {
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
        if (account == null || !props.getMarginMode().equalsIgnoreCase(account.getMarginMode())
                || !props.getPositionMode().equalsIgnoreCase(account.getPositionMode())) {
            return result(HtxContractTradeResult.Code.REJECT_PREFLIGHT, contractCode, leverage,
                    "expected isolated/dual_side contract account");
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

    private HtxContractTradeResult checkAvailableLeverage(String contractCode, int leverage) {
        HtxRestResponse<List<HtxSwapAvailableLeverage>> response = swap().availableLeverage(contractCode);
        HtxSwapAvailableLeverage available = response.ok()
                ? payload(response).stream().filter(v -> contractCode.equalsIgnoreCase(v.getContractCode())).findFirst().orElse(null)
                : null;
        if (available == null || !containsLeverage(available.getAvailableLevelRate(), leverage)) {
            return result(HtxContractTradeResult.Code.REJECT_LEVERAGE, contractCode, leverage,
                    response.ok() ? "leverage not available: " + (available == null ? null : available.getAvailableLevelRate())
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

    private static final class FillOutcome {
        private boolean fullyFilled;
        private boolean terminal;
        private boolean terminalRejected;
        private boolean infraFailure;
        private BigDecimal tradeVolume = BigDecimal.ZERO;

        private static FillOutcome from(HtxSwapOrder order) {
            FillOutcome outcome = new FillOutcome();
            outcome.tradeVolume = order.getTradeVolume() == null ? BigDecimal.ZERO : order.getTradeVolume();
            outcome.fullyFilled = Integer.valueOf(6).equals(order.getStatus());
            outcome.terminal = Integer.valueOf(5).equals(order.getStatus())
                    || Integer.valueOf(7).equals(order.getStatus());
            outcome.terminalRejected = outcome.terminal && outcome.tradeVolume.signum() == 0;
            return outcome;
        }
    }
}
