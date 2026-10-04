package com.metaverse.coin.strategy.htx.controller;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.htx.service.HtxContractCycleRequest;
import com.metaverse.coin.strategy.htx.replay.HtxTrendReplayService;
import com.metaverse.coin.strategy.htx.service.HtxContractLeverageRequest;
import com.metaverse.coin.strategy.htx.service.HtxContractTradeResult;
import com.metaverse.coin.strategy.htx.service.HtxContractTradingService;
import com.metaverse.coin.strategy.htx.service.HtxTrendPaperExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operational API for guarded isolated USDT-margined perpetual trading. */
@RestController
@RequestMapping("/htx/contract")
@RequiredArgsConstructor
@Validated
@ConditionalOnBean(HtxUsdtSwapApiService.class)
public class HtxContractController {

    private final HtxUsdtSwapApiService api;
    private final HtxContractTradingService tradingService;
    private final HtxTrendReplayService trendReplayService;
    private final HtxTrendPaperExecutionService paperExecutionService;

    @GetMapping("/depth")
    public HtxRestResponse<HtxDepth> depth(@RequestParam String contractCode,
                                           @RequestParam(defaultValue = "6") int step) {
        return api.getSwapService().depth(contractCode, step);
    }

    @PostMapping("/leverage")
    public HtxContractTradeResult configureLeverage(@RequestBody HtxContractLeverageRequest request) {
        return tradingService.configureLeverage(request);
    }

    @PostMapping("/long-cycle")
    public HtxContractTradeResult submitLongCycle(@RequestBody HtxContractCycleRequest request) {
        return tradingService.submitLongCycle(request);
    }

    @PostMapping("/dual-cycle")
    public HtxContractTradeResult submitDualCycle(@RequestBody HtxContractCycleRequest request) {
        return tradingService.submitDualCycle(request);
    }

    /** Freezes public historical K-lines and writes a replay ledger; no trade, cancel, or position API is called. */
    @PostMapping("/trend-replay")
    public HtxTrendReplayService.ReplaySummary replayTrend(@Validated @RequestBody HtxTrendReplayRequest request) {
        return trendReplayService.replay(request.getStartAt(), request.getEndAt());
    }

    /** Paper-only callback. It persists a simulated result and does not invoke any exchange API. */
    @PostMapping("/trend-paper-executions")
    public HtxTrendPaperExecutionService.Result reportPaperExecution(
            @RequestBody HtxTrendPaperExecutionService.Report request) {
        return paperExecutionService.report(request);
    }

    /** Paper-only lifecycle callback. It records order-state evidence and never invokes HTX. */
    @PostMapping("/trend-paper-execution-events")
    public HtxTrendPaperExecutionService.LifecycleResult reportPaperExecutionEvent(
            @RequestBody HtxTrendPaperExecutionService.LifecycleEvent request) {
        return paperExecutionService.reportEvent(request);
    }
}
