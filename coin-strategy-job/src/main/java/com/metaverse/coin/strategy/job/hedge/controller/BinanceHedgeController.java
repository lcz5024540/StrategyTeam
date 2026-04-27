package com.metaverse.coin.strategy.job.hedge.controller;

import com.metaverse.coin.strategy.job.service.BinanceHedgeStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/binance/strategy")
@RequiredArgsConstructor
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class BinanceHedgeController {

    private final BinanceHedgeStrategyService hedgeStrategyService;

    /**
     * Trigger the Hedge Strategy (Open Dual Positions -> Wait 30s -> Close)
     * @param symbol Symbol (e.g., BTCUSDT)
     * @param amount USDT Amount per side (e.g., 10)
     * @param mode   Strategy Mode: "futures" (Default) or "spot-futures"
     * @return Execution Log
     */
    @PostMapping("/hedge")
    public String executeHedge(@RequestParam String symbol, @RequestParam BigDecimal amount, 
                               @RequestParam(defaultValue = "futures") String mode) {
        boolean useSpotFutures = "spot-futures".equalsIgnoreCase(mode);
        return hedgeStrategyService.executeHedgeStrategy(symbol, amount, useSpotFutures);
    }
}
