package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesSymbol;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceSymbol;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private static final int INTERNAL_SCALE = 8;
    private static final int DEFAULT_LEVERAGE = 3;

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
        SpotFuturesOrderPlan plan = SpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(futuresSymbol)
                .quantity(quantity)
                .build();
        return openArbitragePosition(plan);
    }

    public SpotFuturesOrderPlan createSpotFuturesPlan(String symbol, BigDecimal usdtAmount) {
        return createSpotFuturesPlan(symbol, usdtAmount, DEFAULT_LEVERAGE);
    }

    public SpotFuturesOrderPlan createSpotFuturesPlan(String symbol, BigDecimal usdtAmount, Integer leverage) {
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (usdtAmount == null || usdtAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("usdtAmount must be greater than 0");
        }

        BinanceSymbol spotSymbol = getSpotSymbol(symbol);
        BinanceFuturesSymbol futuresSymbol = getFuturesSymbol(symbol);
        BigDecimal spotPrice = getSpotPrice(symbol);
        BigDecimal futuresPrice = getFuturesPrice(symbol);
        BigDecimal referencePrice = spotPrice.max(futuresPrice);

        BigDecimal spotStep = getQuantityStep(spotSymbol.getFilters(), spotSymbol.getBaseAssetPrecision());
        BigDecimal futuresStep = getQuantityStep(futuresSymbol.getFilters(), futuresSymbol.getQuantityPrecision());
        BigDecimal quantityStep = spotStep.max(futuresStep);

        BigDecimal spotMinQty = getMinQuantity(spotSymbol.getFilters(), spotStep);
        BigDecimal futuresMinQty = getMinQuantity(futuresSymbol.getFilters(), futuresStep);
        BigDecimal minQuantity = spotMinQty.max(futuresMinQty);

        BigDecimal rawQuantity = usdtAmount.divide(referencePrice, INTERNAL_SCALE, RoundingMode.DOWN);
        BigDecimal quantity = roundDownToStep(rawQuantity, quantityStep);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0 || quantity.compareTo(minQuantity) < 0) {
            throw new IllegalArgumentException("Calculated quantity " + quantity + " is below minimum tradable quantity " + minQuantity + " for " + symbol);
        }

        BigDecimal spotNotional = quantity.multiply(spotPrice);
        BigDecimal futuresNotional = quantity.multiply(futuresPrice);
        BigDecimal minNotional = getMinNotional(spotSymbol.getFilters()).max(getMinNotional(futuresSymbol.getFilters()));
        BigDecimal actualMinNotional = spotNotional.min(futuresNotional);

        if (actualMinNotional.compareTo(minNotional) < 0) {
            throw new IllegalArgumentException("Calculated notional " + actualMinNotional + " is below minimum required " + minNotional + " for " + symbol);
        }

        return SpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(symbol)
                .quantity(quantity)
                .spotPrice(spotPrice)
                .futuresPrice(futuresPrice)
                .referencePrice(referencePrice)
                .spotNotional(spotNotional)
                .futuresNotional(futuresNotional)
                .quantityStep(quantityStep)
                .minQuantity(minQuantity)
                .minNotional(minNotional)
                .leverage(leverage == null || leverage <= 0 ? DEFAULT_LEVERAGE : leverage)
                .build();
    }

    public ArbitrageExecutionResult openArbitragePosition(SpotFuturesOrderPlan plan) {
        BigDecimal quantity = requireQuantity(plan);
        String symbol = plan.getSpotSymbol();
        String futuresSymbol = plan.getFuturesSymbol();
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
                return binanceApiService.openShort(futuresSymbol, "MARKET", quantity.toPlainString(), null, null);
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
                return ArbitrageExecutionResult.success(plan.getSymbol(), quantity.toPlainString(), String.valueOf(spotOrder.getOrderId()), String.valueOf(futuresOrder.getOrderId()));
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
            if ("BUY".equalsIgnoreCase(side)) {
                binanceApiService.closeShort(symbol, "MARKET", quantity.toPlainString(), null);
            } else {
                binanceApiService.closeLong(symbol, "MARKET", quantity.toPlainString(), null);
            }
            log.info("Rollback Futures {} success in hedge mode.", side);
        } catch (Exception e) {
            log.error("CRITICAL: Failed to rollback Futures {} order!", side, e);
            // TODO: Alert human operator immediately
        }
    }

    /**
     * Close Arbitrage Position (Sell Spot + Buy Futures)
     */
    public boolean closeArbitragePosition(String symbol, String futuresSymbol, BigDecimal quantity) {
        SpotFuturesOrderPlan plan = SpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(futuresSymbol)
                .quantity(quantity)
                .build();
        return closeArbitragePosition(plan);
    }

    public boolean closeArbitragePosition(SpotFuturesOrderPlan plan) {
        BigDecimal quantity = requireQuantity(plan);
        String symbol = plan.getSpotSymbol();
        String futuresSymbol = plan.getFuturesSymbol();
        log.info("Closing Arbitrage Position: Sell {} Spot, Buy {} Futures, Quantity: {}", symbol, futuresSymbol, quantity);

        // Spot Sell
        CompletableFuture<BinanceOrder> spotFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return binanceApiService.placeSpotOrder(symbol, "SELL", "MARKET", null, quantity.toPlainString(), null);
            } catch (Exception e) {
                log.error("Failed to place Spot Sell order", e);
                return null;
            }
        });

        // Futures Buy (Short Close)
        CompletableFuture<BinanceFuturesOrder> futuresFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return binanceApiService.closeShort(futuresSymbol, "MARKET", quantity.toPlainString(), null);
            } catch (Exception e) {
                log.error("Failed to place Futures Buy order", e);
                return null;
            }
        });

        try {
            CompletableFuture.allOf(spotFuture, futuresFuture).join();
            BinanceOrder spotOrder = spotFuture.get();
            BinanceFuturesOrder futuresOrder = futuresFuture.get();

            if (spotOrder != null && futuresOrder != null) {
                return true;
            }
            if (spotOrder != null) {
                log.error("Legging Risk on close: Spot close succeeded but Futures close failed. Re-opening Spot.");
                rollbackSpot(symbol, "BUY", quantity);
            }
            if (futuresOrder != null) {
                log.error("Legging Risk on close: Futures close succeeded but Spot close failed. Re-opening Futures short.");
                reopenFuturesShort(futuresSymbol, quantity);
            }
            return false;
        } catch (Exception e) {
            log.error("Error closing dual-side order", e);
            return false;
        }
    }

    private void reopenFuturesShort(String symbol, BigDecimal quantity) {
        try {
            binanceApiService.openShort(symbol, "MARKET", quantity.toPlainString(), null, null);
            log.info("Rollback reopen futures short success.");
        } catch (Exception e) {
            log.error("CRITICAL: Failed to rollback close flow on futures side!", e);
        }
    }

    private BigDecimal requireQuantity(SpotFuturesOrderPlan plan) {
        if (plan == null || plan.getQuantity() == null || plan.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("plan quantity must be greater than 0");
        }
        return plan.getQuantity();
    }

    private BinanceSymbol getSpotSymbol(String symbol) {
        BinanceExchangeInfo exchangeInfo = binanceApiService.exchangeInfo(symbol, null);
        if (exchangeInfo == null || exchangeInfo.getSymbols() == null || exchangeInfo.getSymbols().isEmpty()) {
            throw new IllegalArgumentException("Spot symbol metadata not found for " + symbol);
        }
        return exchangeInfo.getSymbols().stream()
                .filter(item -> symbol.equalsIgnoreCase(item.getSymbol()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Spot symbol metadata not found for " + symbol));
    }

    private BinanceFuturesSymbol getFuturesSymbol(String symbol) {
        BinanceFuturesExchangeInfo exchangeInfo = binanceApiService.getFuturesService().getExchangeInfo();
        if (exchangeInfo == null || exchangeInfo.getSymbols() == null) {
            throw new IllegalArgumentException("Futures symbol metadata not found for " + symbol);
        }
        return exchangeInfo.getSymbols().stream()
                .filter(item -> symbol.equalsIgnoreCase(item.getSymbol()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Futures symbol metadata not found for " + symbol));
    }

    private BigDecimal getSpotPrice(String symbol) {
        BinancePriceTicker ticker = binanceApiService.getTickerPrice(symbol);
        if (ticker == null || ticker.getPrice() == null) {
            throw new IllegalArgumentException("Spot price not available for " + symbol);
        }
        return ticker.getPrice();
    }

    private BigDecimal getFuturesPrice(String symbol) {
        BinancePriceTicker ticker = binanceApiService.getFuturesTickerPrice(symbol);
        if (ticker == null || ticker.getPrice() == null) {
            throw new IllegalArgumentException("Futures price not available for " + symbol);
        }
        return ticker.getPrice();
    }

    private BigDecimal getQuantityStep(List<Map<String, Object>> filters, Integer fallbackPrecision) {
        Optional<BigDecimal> marketStep = extractDecimal(filters, "MARKET_LOT_SIZE", "stepSize");
        Optional<BigDecimal> lotStep = extractDecimal(filters, "LOT_SIZE", "stepSize");
        if (marketStep.isPresent() && marketStep.get().compareTo(BigDecimal.ZERO) > 0) {
            return marketStep.get();
        }
        if (lotStep.isPresent() && lotStep.get().compareTo(BigDecimal.ZERO) > 0) {
            return lotStep.get();
        }
        int precision = fallbackPrecision == null ? 3 : Math.min(fallbackPrecision, INTERNAL_SCALE);
        return BigDecimal.ONE.movePointLeft(precision);
    }

    private BigDecimal getMinQuantity(List<Map<String, Object>> filters, BigDecimal fallbackStep) {
        Optional<BigDecimal> marketMinQty = extractDecimal(filters, "MARKET_LOT_SIZE", "minQty");
        Optional<BigDecimal> lotMinQty = extractDecimal(filters, "LOT_SIZE", "minQty");
        return StreamUtil.firstPositive(marketMinQty.orElse(BigDecimal.ZERO), lotMinQty.orElse(BigDecimal.ZERO), fallbackStep);
    }

    private BigDecimal getMinNotional(List<Map<String, Object>> filters) {
        Optional<BigDecimal> minNotional = extractDecimal(filters, "MIN_NOTIONAL", "minNotional");
        Optional<BigDecimal> notional = extractDecimal(filters, "NOTIONAL", "minNotional");
        return StreamUtil.firstPositive(minNotional.orElse(BigDecimal.ZERO), notional.orElse(BigDecimal.ZERO), BigDecimal.ZERO);
    }

    private Optional<BigDecimal> extractDecimal(List<Map<String, Object>> filters, String filterType, String field) {
        if (filters == null) {
            return Optional.empty();
        }
        return filters.stream()
                .filter(Objects::nonNull)
                .filter(filter -> filterType.equals(String.valueOf(filter.get("filterType"))))
                .map(filter -> filter.get(field))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(BigDecimal::new)
                .max(Comparator.naturalOrder());
    }

    private BigDecimal roundDownToStep(BigDecimal value, BigDecimal step) {
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0) {
            return value;
        }
        BigDecimal steps = value.divide(step, 0, RoundingMode.DOWN);
        return steps.multiply(step).stripTrailingZeros();
    }

    private static final class StreamUtil {
        private static BigDecimal firstPositive(BigDecimal first, BigDecimal second, BigDecimal fallback) {
            if (first != null && first.compareTo(BigDecimal.ZERO) > 0) {
                return first;
            }
            if (second != null && second.compareTo(BigDecimal.ZERO) > 0) {
                return second;
            }
            return fallback == null ? BigDecimal.ZERO : fallback;
        }
    }
}
