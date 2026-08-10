package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxPriceLevel;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.htx.config.HtxContractProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxContractTradeRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/** Periodically runs one guarded isolated long open/close cycle. */
@Slf4j
@Component
@ConditionalOnBean(HtxUsdtSwapApiService.class)
@ConditionalOnExpression("${htx.contract.auto-test-enabled:false} or ${htx.contract.auto-live-enabled:false}")
@RequiredArgsConstructor
public class HtxContractAutoTestScheduler {

    private final HtxUsdtSwapApiService api;
    private final HtxContractTradingService tradingService;
    private final HtxContractTradeRecorder recorder;
    private final HtxContractProperties props;

    @Scheduled(
            fixedDelayString = "${htx.contract.auto-test-interval-ms:60000}",
            initialDelayString = "${htx.contract.auto-test-initial-delay-ms:20000}")
    public void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("[HTX-CONTRACT-AUTO] cycle failed", e);
        }
    }

    void runOnce() {
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
        HtxSwapContractInfo contract = loadContract(contractCode);
        HtxPriceLevel bestAsk = loadBestAsk(contractCode);
        if (contract == null || contract.getPriceTick() == null || contract.getPriceTick().signum() <= 0
                || bestAsk == null || bestAsk.getPrice() == null || bestAsk.getPrice().signum() <= 0) {
            log.warn("[HTX-CONTRACT-AUTO] public market data unavailable contract={}", contractCode);
            return;
        }

        BigDecimal openPrice = bestAsk.getPrice();
        BigDecimal closePrice = roundUpToTick(openPrice.multiply(
                BigDecimal.ONE.add(props.getAutoTestTargetReturnPct())), contract.getPriceTick());
        long clientOrderId = System.currentTimeMillis() * 10L;
        HtxContractCycleRequest request = HtxContractCycleRequest.builder()
                .contractCode(contractCode)
                .volume(props.getAutoTestVolume())
                .openPrice(openPrice)
                .closePrice(closePrice)
                .leverage(props.getLeverage())
                .clientOrderId(clientOrderId)
                .waitFillTimeoutMs(props.getFillTimeoutMs())
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
        HtxContractTradeResult result = tradingService.submitLongCycle(request);
        if (!result.success()) {
            log.warn("[HTX-CONTRACT-AUTO] cycle rejected contract={} result={}", contractCode, result);
            return;
        }
        if (dryRunMode) {
            long cycleId = recorder.recordDryRun(request, contractCode, props.getLeverage(), result.getMessage());
            result.setCycleId(cycleId);
        }
        log.info("[HTX-CONTRACT-AUTO] completed mode={} cycleId={} contract={} volume={} leverage={}x open={} close={}",
                dryRunMode ? "dry-run" : "live", result.getCycleId(), contractCode,
                props.getAutoTestVolume(), props.getLeverage(), openPrice, closePrice);
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

    private HtxPriceLevel loadBestAsk(String contractCode) {
        HtxRestResponse<HtxDepth> response = api.getSwapService().depth(contractCode, props.getDepthStep());
        HtxDepth depth = response == null ? null : response.payload();
        return depth == null || depth.getAsks() == null || depth.getAsks().isEmpty()
                ? null : depth.getAsks().get(0);
    }

    static BigDecimal roundUpToTick(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.CEILING).multiply(tick);
    }
}
