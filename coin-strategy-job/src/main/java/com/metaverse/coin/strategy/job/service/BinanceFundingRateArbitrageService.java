package com.metaverse.coin.strategy.job.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;

/**
 * Binance Funding Rate Arbitrage Strategy
 * Strategy:
 * 1. Monitor Funding Rate.
 * 2. If Funding Rate > Threshold (positive), Open Long Spot + Short Futures.
 * 3. Capture Funding Fees (paid by Longs to Shorts).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceFundingRateArbitrageService {

    private final BinanceApiService binanceApiService;

    /**
     * Execute Arbitrage Entry
     * @param symbol Symbol (e.g., BTCUSDT)
     * @param usdtAmount Total USDT Amount to deploy (split 50/50 ideally, or per side)
     *                   Here we interpret it as Amount PER SIDE to ensure delta neutral.
     * @param minFundingRate Minimum Funding Rate to trigger (e.g., 0.0001 for 0.01%)
     * @return Execution Log
     */
    public String executeArbitrage(String symbol, BigDecimal usdtAmount, BigDecimal minFundingRate) {
        StringBuilder resultLog = new StringBuilder();
        logAndAppend(resultLog, "=== Starting Funding Rate Arbitrage for " + symbol + " ===");
        logAndAppend(resultLog, "Amount Per Side: " + usdtAmount + " USDT");
        logAndAppend(resultLog, "Min Funding Rate: " + minFundingRate);

        try {
            // 1. Check Funding Rate
            BigDecimal currentRate = getFundingRate(symbol);
            logAndAppend(resultLog, "Current Funding Rate: " + currentRate);

            if (currentRate.compareTo(minFundingRate) < 0) {
                logAndAppend(resultLog, "Funding Rate too low. Strategy Aborted.");
                return resultLog.toString();
            }

            // 2. Check Balances
            checkBalances(usdtAmount, resultLog);

            // 3. Calculate Quantity
            BigDecimal price = getCurrentPrice(symbol);
            int precision = getPrecision(symbol);
            BigDecimal quantity = usdtAmount.divide(price, precision, RoundingMode.DOWN);
            
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("Calculated quantity is too small: " + quantity);
            }
            logAndAppend(resultLog, "Reference Price: " + price + ", Quantity: " + quantity);

            // 4. Execute Orders (Parallel)
            logAndAppend(resultLog, ">> Opening Positions (Long Spot + Short Futures)...");
            openPositions(symbol, quantity, resultLog);

            logAndAppend(resultLog, "=== Arbitrage Positions Opened Successfully ===");

        } catch (Exception e) {
            log.error("Arbitrage Strategy Failed", e);
            logAndAppend(resultLog, "!!! Arbitrage Strategy Failed: " + e.getMessage());
        }

        return resultLog.toString();
    }

    private BigDecimal getFundingRate(String symbol) {
        String response = binanceApiService.getFuturesFundingRate(symbol);
        // Response: {"symbol":"BTCUSDT","markPrice":"...","lastFundingRate":"0.0001",...}
        JSONObject json = JSON.parseObject(response);
        return json.getBigDecimal("lastFundingRate");
    }

    private void checkBalances(BigDecimal requiredAmount, StringBuilder logBuffer) {
        // Futures Balance
        BigDecimal futuresBalance = getBalance(binanceApiService.getFuturesAccount(), "USDT");
        // Spot Balance
        BigDecimal spotBalance = getBalance(binanceApiService.account(), "USDT");

        logAndAppend(logBuffer, "Futures Balance: " + futuresBalance + " USDT");
        logAndAppend(logBuffer, "Spot Balance: " + spotBalance + " USDT");

        if (futuresBalance.compareTo(requiredAmount) < 0) {
            logAndAppend(logBuffer, "WARNING: Futures Balance might be insufficient.");
        }
        if (spotBalance.compareTo(requiredAmount) < 0) {
            logAndAppend(logBuffer, "WARNING: Spot Balance might be insufficient.");
        }
    }

    private BigDecimal getBalance(String jsonResponse, String assetName) {
        try {
            JSONObject json = JSON.parseObject(jsonResponse);
            if (json.containsKey("balances")) { // Spot
                for (Object item : json.getJSONArray("balances")) {
                    JSONObject asset = (JSONObject) item;
                    if (assetName.equals(asset.getString("asset"))) {
                        return asset.getBigDecimal("free");
                    }
                }
            } else if (json.containsKey("assets")) { // Futures
                for (Object item : json.getJSONArray("assets")) {
                    JSONObject asset = (JSONObject) item;
                    if (assetName.equals(asset.getString("asset"))) {
                        return asset.getBigDecimal("availableBalance");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse balance: " + e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getCurrentPrice(String symbol) {
        String tickerStr = binanceApiService.getFuturesTickerPrice(symbol);
        JSONObject json = JSON.parseObject(tickerStr);
        return json.getBigDecimal("price");
    }

    private void openPositions(String symbol, BigDecimal quantity, StringBuilder logBuffer) {
        String qtyStr = quantity.toPlainString();

        CompletableFuture<String> spotBuyFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return binanceApiService.spotBuy(symbol, "MARKET", qtyStr, null);
            } catch (Exception e) {
                return "Spot Buy Error: " + e.getMessage();
            }
        });

        CompletableFuture<String> futuresShortFuture = CompletableFuture.supplyAsync(() -> {
            try {
                // Ensure leverage is appropriate? 
                // binanceApiService.changeInitialLeverage(symbol, 1); // Ideally 1x for pure arbitrage to avoid liquidation risk, but user might want leverage.
                // For now, we assume user sets leverage or we use existing. 
                // Ideally, funding rate arbitrage uses 1x leverage on Futures to match Spot perfectly without liquidation risk (except extreme spread).
                return binanceApiService.openShort(symbol, "MARKET", qtyStr, null, null);
            } catch (Exception e) {
                return "Futures Short Error: " + e.getMessage();
            }
        });

        CompletableFuture.allOf(spotBuyFuture, futuresShortFuture).join();

        try {
            logAndAppend(logBuffer, "Spot Buy Result: " + spotBuyFuture.get());
            logAndAppend(logBuffer, "Futures Short Result: " + futuresShortFuture.get());
        } catch (Exception e) {
            logAndAppend(logBuffer, "Error getting results: " + e.getMessage());
        }
    }

    private void logAndAppend(StringBuilder buffer, String msg) {
        log.info(msg);
        buffer.append(msg).append("\n");
    }

    private int getPrecision(String symbol) {
        // TODO: Retrieve from Exchange Info
        if (symbol.startsWith("BTC")) return 3;
        if (symbol.startsWith("ETH")) return 3;
        if (symbol.startsWith("SOL")) return 2;
        if (symbol.startsWith("XRP")) return 1;
        return 2;
    }
}
