package com.metaverse.coin.strategy.job.controller;

import com.metaverse.coin.strategy.job.service.BinanceSingleExchangeFundingRateArbitrageService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/binance/strategy/arbitrage")
@RequiredArgsConstructor
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class BinanceSingleExchangeArbitrageController {

    private final BinanceSingleExchangeFundingRateArbitrageService arbitrageService;

    /**
     * Trigger Funding Rate Arbitrage (Open Long Spot + Short Futures if rate > threshold)
     * @param symbol Symbol (e.g., BTCUSDT)
     * @param symbols Comma-separated symbols (e.g., BTCUSDT,ETHUSDT)
     * @param amount USDT Amount per side
     * @param minRate Minimum Funding Rate to trigger (e.g., 0.0001)
     * @return Execution Log
     */
    @PostMapping("/execute")
    public String executeArbitrage(@RequestParam(required = false) String symbol,
                                   @RequestParam(required = false) String symbols,
                                   @RequestParam BigDecimal amount,
                                   @RequestParam(defaultValue = "0.0001") BigDecimal minRate,
                                   @RequestParam(defaultValue = "3") Integer leverage) {
        String targetSymbols = symbols;
        if ((targetSymbols == null || targetSymbols.trim().isEmpty()) && symbol != null && !symbol.trim().isEmpty()) {
            targetSymbols = symbol;
        }
        return arbitrageService.executeArbitrage(targetSymbols, amount, minRate, leverage);
    }
}
