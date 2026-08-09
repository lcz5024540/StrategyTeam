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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/** Periodically simulates one isolated long open/close cycle and writes an audit row. */
@Slf4j
@Component
@ConditionalOnBean(HtxUsdtSwapApiService.class)
@ConditionalOnProperty(prefix = "htx.contract", name = "auto-test-enabled", havingValue = "true")
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
            log.error("[HTX-CONTRACT-AUTO-TEST] cycle failed", e);
        }
    }

    void runOnce() {
        if (!props.isDryRun() || props.isLiveEnabled()) {
            log.error("[HTX-CONTRACT-AUTO-TEST] refused because dry-run safety gate is not active");
            return;
        }
        if (props.getContractCodes() == null || props.getContractCodes().size() != 1) {
            log.error("[HTX-CONTRACT-AUTO-TEST] exactly one contract code is required");
            return;
        }
        String contractCode = props.getContractCodes().get(0).trim().toUpperCase(Locale.ROOT);
        HtxSwapContractInfo contract = loadContract(contractCode);
        HtxPriceLevel bestAsk = loadBestAsk(contractCode);
        if (contract == null || contract.getPriceTick() == null || contract.getPriceTick().signum() <= 0
                || bestAsk == null || bestAsk.getPrice() == null || bestAsk.getPrice().signum() <= 0) {
            log.warn("[HTX-CONTRACT-AUTO-TEST] public market data unavailable contract={}", contractCode);
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

        HtxContractTradeResult leverage = tradingService.configureLeverage(
                new HtxContractLeverageRequest(contractCode, props.getLeverage()));
        if (!leverage.success()) {
            log.warn("[HTX-CONTRACT-AUTO-TEST] leverage simulation rejected contract={} result={}",
                    contractCode, leverage);
            return;
        }
        HtxContractTradeResult result = tradingService.submitLongCycle(request);
        if (!result.success()) {
            log.warn("[HTX-CONTRACT-AUTO-TEST] cycle rejected contract={} result={}", contractCode, result);
            return;
        }
        long cycleId = recorder.recordDryRun(request, contractCode, props.getLeverage(), result.getMessage());
        result.setCycleId(cycleId);
        log.info("[HTX-CONTRACT-AUTO-TEST] completed cycleId={} contract={} volume={} leverage={}x open={} close={}",
                cycleId, contractCode, props.getAutoTestVolume(), props.getLeverage(), openPrice, closePrice);
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
