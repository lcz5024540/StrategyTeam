package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * Order Executor
 * Handles trade execution for Spot and Futures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderExecutor {

    private final BinanceApiService binanceApiService;

    /**
     * Execute Dual-Side Order (Long Spot + Short Futures)
     * Using parallel execution to minimize legging risk.
     * Handles Legging Risk by rolling back if one side fails.
     *
     * @param symbol Spot Symbol (e.g., BTCUSDT)
     * @param futuresSymbol Futures Symbol (e.g., BTCUSDT)
     * @param quantity Amount to trade (in base asset, e.g., BTC amount)
     * @return Execution Result
     */
    public ArbitrageExecutionResult openArbitragePosition(String symbol, String futuresSymbol, BigDecimal quantity) {
        log.info("Opening Arbitrage Position: Long {} Spot, Short {} Futures, Quantity: {}", symbol, futuresSymbol, quantity);

        // Spot Long
        CompletableFuture<BinanceOrder> spotFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return binanceApiService.placeSpotOrder(symbol, "BUY", "MARKET", null, quantity.toPlainString(), null);
            } catch (Exception e) {
                log.error("Failed to place Spot Long order", e);
                return null;
            }
        });

        // Futures Short
        CompletableFuture<BinanceFuturesOrder> futuresFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return binanceApiService.placeFuturesOrder(futuresSymbol, "SELL", null, "MARKET", null, quantity.toPlainString(), null, false);
            } catch (Exception e) {
                log.error("Failed to place Futures Short order", e);
                return null;
            }
        });

        try {
            CompletableFuture.allOf(spotFuture, futuresFuture).join();
            BinanceOrder spotOrder = spotFuture.get();
            BinanceFuturesOrder futuresOrder = futuresFuture.get();

            // Case 1: Both Success
            if (spotOrder != null && futuresOrder != null) {
                log.info("Arbitrage Position Opened. Spot Order ID: {}, Futures Order ID: {}", spotOrder.getOrderId(), futuresOrder.getOrderId());
                return ArbitrageExecutionResult.success(String.valueOf(spotOrder.getOrderId()), String.valueOf(futuresOrder.getOrderId()));
            }

            // Case 2: Spot Success, Futures Fail -> Rollback Spot
            if (spotOrder != null && futuresOrder == null) {
                log.error("Legging Risk: Spot Success, Futures Failed. Rolling back Spot...");
                rollbackSpot(symbol, "SELL", quantity);
                return ArbitrageExecutionResult.failure("Legging Risk: Spot Success, Futures Failed. Rolled back Spot.");
            }

            // Case 3: Spot Fail, Futures Success -> Rollback Futures
            if (spotOrder == null && futuresOrder != null) {
                log.error("Legging Risk: Spot Failed, Futures Success. Rolling back Futures...");
                rollbackFutures(futuresSymbol, "BUY", quantity);
                return ArbitrageExecutionResult.failure("Legging Risk: Spot Failed, Futures Success. Rolled back Futures.");
            }

            // Case 4: Both Fail
            return ArbitrageExecutionResult.failure("Both orders failed.");

        } catch (Exception e) {
            log.error("Error executing dual-side order", e);
            return ArbitrageExecutionResult.failure("Exception during execution: " + e.getMessage());
        }
    }

    private void rollbackSpot(String symbol, String side, BigDecimal quantity) {
        try {
            binanceApiService.placeSpotOrder(symbol, side, "MARKET", null, quantity.toPlainString(), null);
            log.info("Rollback Spot {} success.", side);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to rollback Spot {} order!", side, e);
            // TODO: Alert human operator immediately
        }
    }

    private void rollbackFutures(String symbol, String side, BigDecimal quantity) {
        try {
            binanceApiService.placeFuturesOrder(symbol, side, null, "MARKET", null, quantity.toPlainString(), null, false);
            log.info("Rollback Futures {} success.", side);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to rollback Futures {} order!", side, e);
            // TODO: Alert human operator immediately
        }
    }

    /**
     * Close Arbitrage Position (Sell Spot + Buy Futures)
     */
    public boolean closeArbitragePosition(String symbol, String futuresSymbol, BigDecimal quantity) {
        log.info("Closing Arbitrage Position: Sell {} Spot, Buy {} Futures, Quantity: {}", symbol, futuresSymbol, quantity);

        // Spot Sell
        CompletableFuture<BinanceOrder> spotFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return binanceApiService.placeSpotOrder(symbol, "SELL", "MARKET", null, quantity.toPlainString(), null);
            } catch (Exception e) {
                log.error("Failed to place Spot Sell order", e);
                throw e;
            }
        });

        // Futures Buy (Short Close)
        CompletableFuture<BinanceFuturesOrder> futuresFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return binanceApiService.placeFuturesOrder(futuresSymbol, "BUY", null, "MARKET", null, quantity.toPlainString(), null, false);
            } catch (Exception e) {
                log.error("Failed to place Futures Buy order", e);
                throw e;
            }
        });

        try {
            CompletableFuture.allOf(spotFuture, futuresFuture).join();
            return true;
        } catch (Exception e) {
            log.error("Error closing dual-side order", e);
            return false;
        }
    }
}
