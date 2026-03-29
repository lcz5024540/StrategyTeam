package com.metaverse.coin.strategy.job.service;

import com.alibaba.fastjson2.JSON;
import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.core.arbitrage.execution.OrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SpotFuturesOrderPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceHedgeStrategyService {

    private final BinanceApiService binanceApiService;
    private final OrderExecutor orderExecutor;

    /**
     * Execute Hedge Strategy
     * 1. Check API & Balance & Settings & Market Data
     * 2. Open Long & Short (Same quantity, same time)
     * 3. Wait 30s
     * 4. Close Long & Short
     *
     * @param symbol     Trading pair (e.g., BTCUSDT)
     * @param usdtAmount Total USDT amount to use (split between long and short? Or for each? 
     *                   User said "Total amount consistent", usually means Per Side Amount = X, or Total = 2X. 
     *                   I will assume 'usdtAmount' is the amount PER SIDE to ensure "amounts are consistent".)
     */
    public String executeHedgeStrategy(String symbol, BigDecimal usdtAmount) {
        return executeHedgeStrategy(symbol, usdtAmount, false);
    }

    /**
     * Execute Hedge Strategy
     * @param useSpotFutures If true, use Spot Buy + Futures Short (Spot-Futures Arbitrage Mode)
     *                       If false, use Futures Long + Futures Short (Futures Hedge Mode)
     */
    public String executeHedgeStrategy(String symbol, BigDecimal usdtAmount, boolean useSpotFutures) {
        StringBuilder resultLog = new StringBuilder();
        String modeName = useSpotFutures ? "Spot-Futures Arbitrage" : "Futures Dual-Side Hedge";
        logAndAppend(resultLog, "=== Starting " + modeName + " for " + symbol + " with Amount " + usdtAmount + " USDT ===");

        try {
            // 1. Preparation & Checks
            checkPreConditions(symbol, usdtAmount, resultLog, useSpotFutures);
            checkExistingPositions(symbol, resultLog);

            // 2. Build a trade plan from exchange metadata so quantity works across symbols.
            SpotFuturesOrderPlan plan = orderExecutor.createSpotFuturesPlan(symbol, usdtAmount, 3);
            BigDecimal quantity = plan.getQuantity();
            logAndAppend(resultLog, "Reference Price: " + plan.getReferencePrice());
            logAndAppend(resultLog, "Spot Price: " + plan.getSpotPrice() + ", Futures Price: " + plan.getFuturesPrice());
            
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("Calculated quantity is too small: " + quantity);
            }
            logAndAppend(resultLog, "Calculated Quantity: " + quantity);

            // 3. Open Positions (Parallel)
            logAndAppend(resultLog, ">> Opening Positions...");
            if (useSpotFutures) {
                openSpotFuturesPositions(symbol, quantity, resultLog);
            } else {
                openFuturesPositions(symbol, quantity, resultLog);
            }

            // 4. Wait 30s
            logAndAppend(resultLog, ">> Waiting 30 seconds...");
            try {
                TimeUnit.SECONDS.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logAndAppend(resultLog, "Interrupted during wait!");
            }

            // 5. Close Positions (Parallel)
            logAndAppend(resultLog, ">> Closing Positions...");
            if (useSpotFutures) {
                closeSpotFuturesPositions(symbol, quantity, resultLog);
            } else {
                closeFuturesPositions(symbol, quantity, resultLog);
            }

            logAndAppend(resultLog, "=== Hedge Strategy Completed Successfully ===");

        } catch (Exception e) {
            log.error("Hedge Strategy Failed", e);
            logAndAppend(resultLog, "!!! Hedge Strategy Failed: " + e.getMessage());
            return resultLog.toString();
        }

        return resultLog.toString();
    }

    private void checkPreConditions(String symbol, BigDecimal requiredAmount, StringBuilder logBuffer, boolean useSpotFutures) {
        // 1.1 Check API Connectivity
        try {
            Long serverTime = binanceApiService.checkFuturesServerTime();
            logAndAppend(logBuffer, "Futures API Check: OK (Server Time: " + serverTime + ")");
            if (useSpotFutures) {
                String spotTime = binanceApiService.checkServerTime();
                logAndAppend(logBuffer, "Spot API Check: OK (Server Time: " + spotTime + ")");
            }
        } catch (Exception e) {
            throw new RuntimeException("API Connectivity Check Failed: " + e.getMessage());
        }

        // 1.2 Check Account Balance
        try {
            // Futures Balance
            BigDecimal futuresBalance = getBalance(binanceApiService.getFuturesAccount(), "USDT");
            logAndAppend(logBuffer, "Futures Wallet Balance (USDT): " + futuresBalance);

            if (useSpotFutures) {
                // Spot Balance
                BigDecimal spotBalance = getBalance(binanceApiService.account(), "USDT");
                logAndAppend(logBuffer, "Spot Wallet Balance (USDT): " + spotBalance);
                
                if (spotBalance.compareTo(requiredAmount) < 0) {
                    logAndAppend(logBuffer, "WARNING: Spot Balance insufficient for Buy.");
                }
            } else {
                if (futuresBalance.compareTo(requiredAmount) < 0) {
                    logAndAppend(logBuffer, "WARNING: Futures Balance might be insufficient.");
                }
            }

        } catch (Exception e) {
            logAndAppend(logBuffer, "Balance Check Warning: " + e.getMessage());
        }

        // 1.3 Check/Set Contract Settings (Only needed for Futures part)
        try {
            logAndAppend(logBuffer, "Skipping forced Position Mode change (Ensure account is in Hedge Mode).");
            binanceApiService.changeInitialLeverage(symbol, 20);
            logAndAppend(logBuffer, "Futures Leverage set to 20x.");
        } catch (Exception e) {
            logAndAppend(logBuffer, "Contract Settings Check Warning: " + e.getMessage());
        }
    }
    
    private void checkExistingPositions(String symbol, StringBuilder logBuffer) {
        try {
            // 1. Check Open Positions
            BinanceFuturesAccount accountInfo = binanceApiService.getFuturesAccount();
            if (accountInfo.getPositions() != null) {
                for (BinanceFuturesAccount.BinanceFuturesAccountPosition position : accountInfo.getPositions()) {
                    if (symbol.equals(position.getSymbol())) {
                        BigDecimal amt = position.getPositionAmt();
                        // Check if absolute value is greater than 0
                        if (amt.abs().compareTo(BigDecimal.ZERO) > 0) {
                            String msg = "Existing Futures Position detected for " + symbol + ": " + amt + ". Strategy aborted to prevent unintended exposure.";
                            logAndAppend(logBuffer, "!!! " + msg);
                            throw new RuntimeException(msg);
                        }
                    }
                }
            }
            logAndAppend(logBuffer, "Position Check: No existing positions for " + symbol + ".");

            // 2. Check Pending Open Orders
            List<BinanceFuturesOrder> openOrders = binanceApiService.getFuturesOpenOrders(symbol);
            if (openOrders != null && !openOrders.isEmpty()) {
                String msg = "Existing Pending Open Orders detected for " + symbol + ". Strategy aborted.";
                logAndAppend(logBuffer, "!!! " + msg);
                throw new RuntimeException(msg);
            }
            logAndAppend(logBuffer, "Open Orders Check: No pending orders for " + symbol + ".");

        } catch (Exception e) {
            if (e instanceof RuntimeException) throw e;
            logAndAppend(logBuffer, "Position/Order Check Failed: " + e.getMessage());
            throw new RuntimeException("Position/Order Check Failed", e);
        }
    }

    private BigDecimal getBalance(BinanceAccountInfo accountInfo, String assetName) {
        if (accountInfo != null && accountInfo.getBalances() != null) {
            for (BinanceAccountInfo.BinanceBalance balance : accountInfo.getBalances()) {
                if (assetName.equals(balance.getAsset())) {
                    return balance.getFree();
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal getBalance(BinanceFuturesAccount accountInfo, String assetName) {
        if (accountInfo != null && accountInfo.getAssets() != null) {
            for (BinanceFuturesAccount.BinanceFuturesAsset asset : accountInfo.getAssets()) {
                if (assetName.equals(asset.getAsset())) {
                    return asset.getAvailableBalance();
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private void openFuturesPositions(String symbol, BigDecimal quantity, StringBuilder logBuffer) throws ExecutionException, InterruptedException {
        String qtyStr = quantity.toPlainString();
        
        CompletableFuture<String> longFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.openLong(symbol, "MARKET", qtyStr, null, null));
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });

        CompletableFuture<String> shortFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.openShort(symbol, "MARKET", qtyStr, null, null));
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });

        CompletableFuture.allOf(longFuture, shortFuture).join();

        logAndAppend(logBuffer, "Open Futures Long Result: " + longFuture.get());
        logAndAppend(logBuffer, "Open Futures Short Result: " + shortFuture.get());
    }
    
    private void openSpotFuturesPositions(String symbol, BigDecimal quantity, StringBuilder logBuffer) throws ExecutionException, InterruptedException {
        String qtyStr = quantity.toPlainString();
        
        // Spot Buy
        CompletableFuture<String> spotBuyFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.spotBuy(symbol, "MARKET", qtyStr, null));
            } catch (Exception e) {
                return "Spot Buy Error: " + e.getMessage();
            }
        });

        // Futures Short
        CompletableFuture<String> futuresShortFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.openShort(symbol, "MARKET", qtyStr, null, null));
            } catch (Exception e) {
                return "Futures Short Error: " + e.getMessage();
            }
        });

        CompletableFuture.allOf(spotBuyFuture, futuresShortFuture).join();

        logAndAppend(logBuffer, "Spot Buy Result: " + spotBuyFuture.get());
        logAndAppend(logBuffer, "Futures Open Short Result: " + futuresShortFuture.get());
    }

    private void closeFuturesPositions(String symbol, BigDecimal quantity, StringBuilder logBuffer) throws ExecutionException, InterruptedException {
        String qtyStr = quantity.toPlainString();

        CompletableFuture<String> closeLongFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.closeLong(symbol, "MARKET", qtyStr, null));
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });

        CompletableFuture<String> closeShortFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.closeShort(symbol, "MARKET", qtyStr, null));
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        });

        CompletableFuture.allOf(closeLongFuture, closeShortFuture).join();

        logAndAppend(logBuffer, "Close Futures Long Result: " + closeLongFuture.get());
        logAndAppend(logBuffer, "Close Futures Short Result: " + closeShortFuture.get());
    }
    
    private void closeSpotFuturesPositions(String symbol, BigDecimal quantity, StringBuilder logBuffer) throws ExecutionException, InterruptedException {
        String qtyStr = quantity.toPlainString();

        // Spot Sell
        CompletableFuture<String> spotSellFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.spotSell(symbol, "MARKET", qtyStr, null));
            } catch (Exception e) {
                return "Spot Sell Error: " + e.getMessage();
            }
        });

        // Futures Close Short
        CompletableFuture<String> futuresCloseShortFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return JSON.toJSONString(binanceApiService.closeShort(symbol, "MARKET", qtyStr, null));
            } catch (Exception e) {
                return "Futures Close Short Error: " + e.getMessage();
            }
        });

        CompletableFuture.allOf(spotSellFuture, futuresCloseShortFuture).join();

        logAndAppend(logBuffer, "Spot Sell Result: " + spotSellFuture.get());
        logAndAppend(logBuffer, "Futures Close Short Result: " + futuresCloseShortFuture.get());
    }

    private void logAndAppend(StringBuilder buffer, String msg) {
        log.info(msg);
        buffer.append(msg).append("\n");
    }
}
