package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxContractTradeRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import com.metaverse.coin.strategy.htx.strategy.contract.ContractGridStrategy;
import com.metaverse.coin.strategy.htx.strategy.contract.FeeAwareContractGridStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/** Periodically runs one guarded isolated long+short open/close cycle. */
@Slf4j
@Component
@ConditionalOnBean(HtxUsdtSwapApiService.class)
@ConditionalOnExpression("${htx.contract.auto-test-enabled:false} or ${htx.contract.auto-live-enabled:false}")
public class HtxContractAutoTestScheduler {

    private final HtxUsdtSwapApiService api;
    private final HtxContractTradingService tradingService;
    private final HtxContractTradeRecorder recorder;
    private final HtxContractProperties props;
    private final ContractGridStrategy gridStrategy;
    private volatile long nextMarginRetryAtMs;

    @Autowired
    public HtxContractAutoTestScheduler(HtxUsdtSwapApiService api,
                                        HtxContractTradingService tradingService,
                                        HtxContractTradeRecorder recorder,
                                        HtxContractProperties props,
                                        ContractGridStrategy gridStrategy) {
        this.api = api;
        this.tradingService = tradingService;
        this.recorder = recorder;
        this.props = props;
        this.gridStrategy = gridStrategy;
    }

    HtxContractAutoTestScheduler(HtxUsdtSwapApiService api,
                                 HtxContractTradingService tradingService,
                                 HtxContractTradeRecorder recorder,
                                 HtxContractProperties props) {
        this(api, tradingService, recorder, props, new FeeAwareContractGridStrategy());
    }

    @Scheduled(
            fixedDelayString = "${htx.contract.auto-test-interval-ms:5000}",
            initialDelayString = "${htx.contract.auto-test-initial-delay-ms:20000}")
    public void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("[HTX-CONTRACT-AUTO] cycle failed", e);
        }
    }

    void runOnce() {
        if (System.currentTimeMillis() < nextMarginRetryAtMs) {
            return;
        }
        boolean dryRunMode = props.isAutoTestEnabled() && props.isDryRun() && !props.isLiveEnabled();
        boolean liveMode = props.isAutoLiveEnabled() && !props.isDryRun() && props.isLiveEnabled();
        if (!dryRunMode && !liveMode) {
            log.error("[HTX-CONTRACT-AUTO] refused because auto mode and trading safety gates disagree");
            return;
        }
        if (props.getContractCodes() == null || props.getContractCodes().size() != 1) {
            log.error("[HTX-CONTRACT-AUTO] exactly one contract code is required");
            return;
        }
        String contractCode = props.getContractCodes().get(0).trim().toUpperCase(Locale.ROOT);
        if (!props.isAutoProfitGridEnabled() || props.isAutoFastIocEnabled()) {
            log.error("[HTX-CONTRACT-AUTO] refused: profit grid must be enabled and fast IOC disabled");
            return;
        }
        if (recorder.hasActiveMakerGridCycle(contractCode)) {
            HtxContractTradeResult reconcile = tradingService.reconcileMakerGridCycle(contractCode);
            log.info("[HTX-CONTRACT-GRID] reconcile contract={} result={}", contractCode, reconcile);
            return;
        }
        if (liveMode && lossCircuitOpen(contractCode)) {
            return;
        }
        HtxSwapContractInfo contract = loadContract(contractCode);
        HtxDepth depth = loadDepth(contractCode);
        if (contract == null || contract.getContractSize() == null || contract.getContractSize().signum() <= 0
                || contract.getPriceTick() == null || contract.getPriceTick().signum() <= 0
                || depth == null || depth.getAsks() == null || depth.getAsks().isEmpty()
                || depth.getBids() == null || depth.getBids().isEmpty()) {
            log.warn("[HTX-CONTRACT-AUTO] public market data unavailable contract={}", contractCode);
            return;
        }

        HtxPriceLevel bestAsk = depth.getAsks().get(0);
        HtxPriceLevel bestBid = depth.getBids().get(0);
        if (bestAsk.getPrice() == null || bestBid.getPrice() == null
                || bestAsk.getPrice().signum() <= 0 || bestBid.getPrice().signum() <= 0
                || bestBid.getPrice().compareTo(bestAsk.getPrice()) > 0) {
            log.warn("[HTX-CONTRACT-AUTO] invalid top of book contract={}", contractCode);
            return;
        }
        BigDecimal referencePrice = bestBid.getPrice().add(bestAsk.getPrice()).divide(BigDecimal.valueOf(2),
                contract.getPriceTick().scale() + 4, RoundingMode.HALF_UP);
        if (!gridStrategy.entrySpreadAllowed(bestBid.getPrice(), bestAsk.getPrice(),
                props.getMaxEntrySpreadRate())) {
            log.info("[HTX-CONTRACT-GRID] entry spread blocked contract={} bid={} ask={} maxRate={}",
                    contractCode, bestBid.getPrice(), bestAsk.getPrice(), props.getMaxEntrySpreadRate());
            return;
        }
        BigDecimal longOpenPrice = roundToTick(bestBid.getPrice(), contract.getPriceTick());
        BigDecimal shortOpenPrice = roundUpToTick(bestAsk.getPrice(), contract.getPriceTick());
        long volume = targetVolume(contract, referencePrice);
        long clientOrderId = System.currentTimeMillis() * 10L;
        HtxContractCycleRequest request = HtxContractCycleRequest.builder()
                .contractCode(contractCode)
                .volume(volume)
                .openPrice(longOpenPrice)
                .closePrice(shortOpenPrice)
                .longOpenPrice(longOpenPrice)
                .shortOpenPrice(shortOpenPrice)
                .leverage(props.getLeverage())
                .clientOrderId(clientOrderId)
                .waitFillTimeoutMs(props.getFillTimeoutMs())
                .fastIoc(false)
                .makerGrid(true)
                .build();

        if (dryRunMode) {
            HtxContractTradeResult leverage = tradingService.configureLeverage(
                    new HtxContractLeverageRequest(contractCode, props.getLeverage()));
            if (!leverage.success()) {
                log.warn("[HTX-CONTRACT-AUTO] leverage simulation rejected contract={} result={}",
                        contractCode, leverage);
                return;
            }
        }
        HtxContractTradeResult result = tradingService.submitDualCycle(request);
        if (HtxContractTradeResult.Code.CLOSE_PENDING.equals(result.getCode())) {
            nextMarginRetryAtMs = 0L;
            log.info("[HTX-CONTRACT-GRID] maker exits resting contract={} cycleId={} result={}",
                    contractCode, result.getCycleId(), result);
            return;
        }
        if (!result.success()) {
            if (HtxContractTradeResult.Code.REJECT_INSUFFICIENT_MARGIN.equals(result.getCode())) {
                nextMarginRetryAtMs = System.currentTimeMillis() + props.getInsufficientMarginBackoffMs();
                log.warn("[HTX-CONTRACT-AUTO] insufficient margin; pause new cycles for {}ms",
                        props.getInsufficientMarginBackoffMs());
            }
            log.warn("[HTX-CONTRACT-AUTO] cycle rejected contract={} result={}", contractCode, result);
            return;
        }
        nextMarginRetryAtMs = 0L;
        if (dryRunMode) {
            long cycleId = recorder.recordDryRun(request, contractCode, props.getLeverage(), result.getMessage());
            result.setCycleId(cycleId);
        }
        log.info("[HTX-CONTRACT-AUTO] completed mode={} cycleId={} contract={} volume={} targetNotional={} "
                        + "leverage={}x longOpen={} shortOpen={} makerGrid=true",
                dryRunMode ? "dry-run" : "live", result.getCycleId(), contractCode,
                volume, props.getAutoTestQuoteNotional(), props.getLeverage(), longOpenPrice, shortOpenPrice);
    }

    private HtxSwapContractInfo loadContract(String contractCode) {
        HtxRestResponse<List<HtxSwapContractInfo>> response = api.getSwapService().contractInfo(contractCode);
        if (response == null || !response.ok() || response.getData() == null) {
            return null;
        }
        return response.getData().stream()
                .filter(item -> contractCode.equalsIgnoreCase(item.getContractCode()))
                .findFirst().orElse(null);
    }

    private HtxDepth loadDepth(String contractCode) {
        HtxRestResponse<HtxDepth> response = api.getSwapService().depth(contractCode, props.getDepthStep());
        return response == null || !response.ok() ? null : response.payload();
    }

    private boolean lossCircuitOpen(String contractCode) {
        try {
            BigDecimal dailyNet = recorder.sumTodayMakerGridNet();
            dailyNet = dailyNet == null ? BigDecimal.ZERO : dailyNet;
            BigDecimal dailyLossCap = props.getMaxDailyRealizedLossUsdt();
            if (dailyLossCap != null && dailyLossCap.signum() > 0
                    && dailyNet.compareTo(dailyLossCap.negate()) <= 0) {
                log.error("[HTX-CONTRACT-GRID] daily loss circuit open contract={} net={} cap={}",
                        contractCode, dailyNet, dailyLossCap);
                return true;
            }
            if (recorder.reachedConsecutiveMakerGridLosses(props.getMaxConsecutiveLosses())) {
                log.error("[HTX-CONTRACT-GRID] consecutive loss circuit open contract={} limit={}",
                        contractCode, props.getMaxConsecutiveLosses());
                return true;
            }
            return false;
        } catch (HtxPersistenceException e) {
            log.error("[HTX-CONTRACT-GRID] loss circuit query failed; refuse new entry contract={}",
                    contractCode, e);
            return true;
        }
    }

    static BigDecimal roundUpToTick(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.CEILING).multiply(tick);
    }

    static BigDecimal roundToTick(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
    }

    private long targetVolume(HtxSwapContractInfo contract, BigDecimal referencePrice) {
        BigDecimal target = props.getAutoTestQuoteNotional();
        if (target == null || target.signum() <= 0) {
            return props.getAutoTestVolume();
        }
        BigDecimal notionalPerContract = contract.getContractSize().multiply(referencePrice);
        return target.divide(notionalPerContract, 0, RoundingMode.HALF_UP)
                .max(BigDecimal.ONE).longValueExact();
    }
}
