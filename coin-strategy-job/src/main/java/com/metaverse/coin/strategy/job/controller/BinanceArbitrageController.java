package com.metaverse.coin.strategy.job.controller;

import com.metaverse.coin.strategy.job.service.BinanceFundingRateArbitrageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/binance/strategy/arbitrage")
@RequiredArgsConstructor
public class BinanceArbitrageController {

    private final BinanceFundingRateArbitrageService arbitrageService;

    /**
     * Trigger Funding Rate Arbitrage (Open Long Spot + Short Futures if rate > threshold)
     * @param symbol Symbol (e.g., BTCUSDT)
     * @param amount USDT Amount per side
     * @param minRate Minimum Funding Rate to trigger (e.g., 0.0001)
     * @return Execution Log
     */
    @PostMapping("/execute")
    public String executeArbitrage(@RequestParam String symbol, 
                                   @RequestParam BigDecimal amount, 
                                   @RequestParam(defaultValue = "0.0001") BigDecimal minRate) {
        return arbitrageService.executeArbitrage(symbol, amount, minRate);
    }
}
