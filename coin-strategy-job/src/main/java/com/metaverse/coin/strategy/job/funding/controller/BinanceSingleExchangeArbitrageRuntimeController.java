package com.metaverse.coin.strategy.job.funding.controller;

import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.decision.SingleExchangeSpotFuturesArbitrageStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/binance/strategy/arbitrage/runtime")
@RequiredArgsConstructor
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class BinanceSingleExchangeArbitrageRuntimeController {

    private final SingleExchangeSpotFuturesArbitrageStrategy strategy;
    private final SingleExchangeSpotFuturesProperties properties;

    @PostMapping("/start")
    public Map<String, Object> start() {
        strategy.start();
        return status("started");
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        strategy.stop();
        return status("stopped");
    }

    @PostMapping("/run-once")
    public Map<String, Object> runOnce() {
        String message = strategy.runOnce();
        return status(message);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return status("ok");
    }

    private Map<String, Object> status(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("strategyId", strategy.getStrategyId());
        result.put("running", strategy.isRunning());
        result.put("symbol", properties.getSymbol());
        result.put("orderExecutionMode", properties.getOrderExecutionMode());
        result.put("tradeUsdtAmount", properties.getTradeUsdtAmount());
        result.put("leverage", properties.getLeverage());
        return result;
    }
}
