package com.metaverse.coin.strategy.core.arbitrage.execution;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SingleExchangeArbitrageOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrder;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesSymbol;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceExchangeInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinancePriceTicker;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceSymbol;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceOrder;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.market.BinanceQueryDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Order Executor
 * Handles trade execution for Spot and Futures.
 */
@Service
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeArbitrageOrderExecutor {

    private static final String EXCHANGE_NAME = "BINANCE";
    private static final String MARKET_TYPE_SPOT = "SPOT";
    private static final String MARKET_TYPE_FUTURES = "FUTURES";
    private static final String TRADE_PHASE_OPEN = "OPEN";
    private static final String TRADE_PHASE_CLOSE = "CLOSE";
    private static final String TRADE_PHASE_ROLLBACK = "ROLLBACK";
    private static final String HEDGE_SIDE_SPOT = "SPOT";
    private static final String HEDGE_SIDE_FUTURES = "FUTURES";
    private static final String ORDER_TYPE_MARKET = "MARKET";
    private static final String POSITION_SIDE_SHORT = "SHORT";
    private static final String POSITION_SIDE_LONG = "LONG";
    private static final String STATUS_FILLED = "FILLED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ORDER_EXECUTION_MODE_BINANCE_TEST = "BINANCE_TEST";
    private static final int INTERNAL_SCALE = 8;
    private static final int DEFAULT_LEVERAGE = 3;
    private final BinanceApiService binanceApiService;
    private final SingleExchangeSpotFuturesProperties properties;
    @Autowired(required = false)
    private BinanceQueryDataProvider queryDataProvider;

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
    public SingleExchangeArbitrageExecutionResult openArbitragePosition(String symbol, String futuresSymbol, BigDecimal quantity) {
        SingleExchangeSpotFuturesOrderPlan plan = SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(futuresSymbol)
                .quantity(quantity)
                .build();
        return openArbitragePosition(plan);
    }

    public SingleExchangeSpotFuturesOrderPlan createSpotFuturesPlan(String symbol, BigDecimal usdtAmount) {
        return createSpotFuturesPlan(symbol, usdtAmount, DEFAULT_LEVERAGE);
    }

    public SingleExchangeSpotFuturesOrderPlan createSpotFuturesPlan(String symbol, BigDecimal usdtAmount, Integer leverage) {
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
        BigDecimal basisSpread = futuresPrice.subtract(spotPrice);
        BigDecimal basisRate = BigDecimal.ZERO;
        if (spotPrice.compareTo(BigDecimal.ZERO) > 0) {
            basisRate = basisSpread.divide(spotPrice, INTERNAL_SCALE, RoundingMode.HALF_UP);
        }

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

        return SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(symbol)
                .quantity(quantity)
                .spotPrice(spotPrice)
                .futuresPrice(futuresPrice)
                .referencePrice(referencePrice)
                .basisSpread(basisSpread)
                .basisRate(basisRate)
                .spotNotional(spotNotional)
                .futuresNotional(futuresNotional)
                .quantityStep(quantityStep)
                .minQuantity(minQuantity)
                .minNotional(minNotional)
                .leverage(leverage == null || leverage <= 0 ? DEFAULT_LEVERAGE : leverage)
                .build();
    }

    public SingleExchangeArbitrageExecutionResult openArbitragePosition(SingleExchangeSpotFuturesOrderPlan plan) {
        BigDecimal quantity = requireQuantity(plan);
        String symbol = plan.getSpotSymbol();
        String futuresSymbol = plan.getFuturesSymbol();
        log.info("Opening Arbitrage Position: Long {} Spot, Short {} Futures, Quantity: {}", symbol, futuresSymbol, quantity);

        CompletableFuture<OrderAttempt<BinanceOrder>> spotFuture = CompletableFuture.supplyAsync(() ->
                placeSpotOrder(symbol, "BUY", quantity, "Failed to place Spot Long order"));
        CompletableFuture<OrderAttempt<BinanceFuturesOrder>> futuresFuture = CompletableFuture.supplyAsync(() ->
                openFuturesShort(futuresSymbol, quantity, "Failed to place Futures Short order"));

        try {
            CompletableFuture.allOf(spotFuture, futuresFuture).join();
            OrderAttempt<BinanceOrder> spotAttempt = spotFuture.get();
            OrderAttempt<BinanceFuturesOrder> futuresAttempt = futuresFuture.get();
            List<SingleExchangeArbitrageOrder> orderRecords = new ArrayList<>();
            orderRecords.add(buildSpotOrderRecord(plan, TRADE_PHASE_OPEN, "BUY", spotAttempt));
            orderRecords.add(buildFuturesOrderRecord(plan, TRADE_PHASE_OPEN, "SELL", POSITION_SIDE_SHORT, futuresAttempt));

            if (spotAttempt.isSuccess() && futuresAttempt.isSuccess()) {
                log.info("Arbitrage Position Opened. Spot Order ID: {}, Futures Order ID: {}",
                        spotAttempt.getOrder().getOrderId(), futuresAttempt.getOrder().getOrderId());
                return SingleExchangeArbitrageExecutionResult.builder()
                        .success(true)
                        .symbol(plan.getSymbol())
                        .quantity(quantity.toPlainString())
                        .spotOrderId(String.valueOf(spotAttempt.getOrder().getOrderId()))
                        .futuresOrderId(String.valueOf(futuresAttempt.getOrder().getOrderId()))
                        .message("Success")
                        .orderRecords(orderRecords)
                        .slippageAlerts(collectSlippageAlerts(plan, orderRecords))
                        .build();
            }

            if (spotAttempt.isSuccess() && !futuresAttempt.isSuccess()) {
                log.error("Legging Risk: Spot Success, Futures Failed. Rolling back Spot...");
                OrderAttempt<BinanceOrder> rollbackAttempt = placeSpotOrder(symbol, "SELL", quantity,
                        "CRITICAL: Failed to rollback Spot SELL order!");
                orderRecords.add(buildSpotOrderRecord(plan, TRADE_PHASE_ROLLBACK, "SELL", rollbackAttempt));
                List<SingleExchangeNakedPositionAlert> nakedAlerts = rollbackAttempt.isSuccess()
                        ? Collections.emptyList()
                        : List.of(buildNakedPositionAlert(plan.getSymbol(), HEDGE_SIDE_SPOT, POSITION_SIDE_LONG,
                                quantity, TRADE_PHASE_OPEN, rollbackAttempt.getErrorMessage()));
                String message = rollbackAttempt.isSuccess()
                        ? "Legging Risk: Spot Success, Futures Failed. Rolled back Spot."
                        : "Legging Risk: Spot Success, Futures Failed. Spot rollback FAILED - naked SPOT LONG position remains.";
                return SingleExchangeArbitrageExecutionResult.builder()
                        .success(false)
                        .symbol(plan.getSymbol())
                        .quantity(quantity.toPlainString())
                        .message(message)
                        .orderRecords(orderRecords)
                        .slippageAlerts(collectSlippageAlerts(plan, orderRecords))
                        .nakedPositionAlerts(nakedAlerts)
                        .build();
            }

            if (!spotAttempt.isSuccess() && futuresAttempt.isSuccess()) {
                log.error("Legging Risk: Spot Failed, Futures Success. Rolling back Futures...");
                OrderAttempt<BinanceFuturesOrder> rollbackAttempt = closeFuturesShort(futuresSymbol, quantity,
                        "CRITICAL: Failed to rollback Futures BUY order!");
                orderRecords.add(buildFuturesOrderRecord(plan, TRADE_PHASE_ROLLBACK, "BUY", POSITION_SIDE_SHORT, rollbackAttempt));
                List<SingleExchangeNakedPositionAlert> nakedAlerts = rollbackAttempt.isSuccess()
                        ? Collections.emptyList()
                        : List.of(buildNakedPositionAlert(plan.getSymbol(), HEDGE_SIDE_FUTURES, POSITION_SIDE_SHORT,
                                quantity, TRADE_PHASE_OPEN, rollbackAttempt.getErrorMessage()));
                String message = rollbackAttempt.isSuccess()
                        ? "Legging Risk: Spot Failed, Futures Success. Rolled back Futures."
                        : "Legging Risk: Spot Failed, Futures Success. Futures rollback FAILED - naked FUTURES SHORT position remains.";
                return SingleExchangeArbitrageExecutionResult.builder()
                        .success(false)
                        .symbol(plan.getSymbol())
                        .quantity(quantity.toPlainString())
                        .message(message)
                        .orderRecords(orderRecords)
                        .slippageAlerts(collectSlippageAlerts(plan, orderRecords))
                        .nakedPositionAlerts(nakedAlerts)
                        .build();
            }

            return SingleExchangeArbitrageExecutionResult.builder()
                    .success(false)
                    .symbol(plan.getSymbol())
                    .quantity(quantity.toPlainString())
                    .message("Both orders failed.")
                    .orderRecords(orderRecords)
                    .slippageAlerts(collectSlippageAlerts(plan, orderRecords))
                    .build();

        } catch (Exception e) {
            log.error("Error executing dual-side order", e);
            return SingleExchangeArbitrageExecutionResult.builder()
                    .success(false)
                    .symbol(plan.getSymbol())
                    .quantity(quantity.toPlainString())
                    .message("Exception during execution: " + e.getMessage())
                    .build();
        }
    }

    private OrderAttempt<BinanceOrder> placeSpotOrder(String symbol, String side, BigDecimal quantity, String errorLogPrefix) {
        try {
            if (isBinanceTestMode()) {
                binanceApiService.testSpotOrder(symbol, side, ORDER_TYPE_MARKET, null, quantity.toPlainString(), null);
                return OrderAttempt.success(buildSimulatedSpotOrder(symbol, side, quantity));
            }
            return OrderAttempt.success(binanceApiService.placeSpotOrder(symbol, side, ORDER_TYPE_MARKET, null,
                    quantity.toPlainString(), null));
        } catch (Exception e) {
            log.error(errorLogPrefix, e);
            return OrderAttempt.failure(e.getMessage());
        }
    }

    private OrderAttempt<BinanceFuturesOrder> openFuturesShort(String symbol, BigDecimal quantity, String errorLogPrefix) {
        try {
            if (isBinanceTestMode()) {
                binanceApiService.testFuturesOrder(symbol, "SELL", POSITION_SIDE_SHORT, ORDER_TYPE_MARKET,
                        null, quantity.toPlainString(), null, null);
                return OrderAttempt.success(buildSimulatedFuturesOrder(symbol, "SELL", POSITION_SIDE_SHORT, quantity));
            }
            return OrderAttempt.success(binanceApiService.openShort(symbol, ORDER_TYPE_MARKET, quantity.toPlainString(), null, null));
        } catch (Exception e) {
            log.error(errorLogPrefix, e);
            return OrderAttempt.failure(e.getMessage());
        }
    }

    /**
     * Close Arbitrage Position (Sell Spot + Buy Futures)
     */
    public SingleExchangeArbitrageExecutionResult closeArbitragePosition(String symbol, String futuresSymbol, BigDecimal quantity) {
        SingleExchangeSpotFuturesOrderPlan plan = SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(futuresSymbol)
                .quantity(quantity)
                .build();
        return closeArbitragePosition(plan);
    }

    public SingleExchangeArbitrageExecutionResult closeArbitragePosition(SingleExchangeSpotFuturesOrderPlan plan) {
        BigDecimal quantity = requireQuantity(plan);
        String symbol = plan.getSpotSymbol();
        String futuresSymbol = plan.getFuturesSymbol();
        log.info("Closing Arbitrage Position: Sell {} Spot, Buy {} Futures, Quantity: {}", symbol, futuresSymbol, quantity);

        CompletableFuture<OrderAttempt<BinanceOrder>> spotFuture = CompletableFuture.supplyAsync(() ->
                placeSpotOrder(symbol, "SELL", quantity, "Failed to place Spot Sell order"));
        CompletableFuture<OrderAttempt<BinanceFuturesOrder>> futuresFuture = CompletableFuture.supplyAsync(() ->
                closeFuturesShort(futuresSymbol, quantity, "Failed to place Futures Buy order"));

        try {
            CompletableFuture.allOf(spotFuture, futuresFuture).join();
            OrderAttempt<BinanceOrder> spotAttempt = spotFuture.get();
            OrderAttempt<BinanceFuturesOrder> futuresAttempt = futuresFuture.get();
            List<SingleExchangeArbitrageOrder> orderRecords = new ArrayList<>();
            orderRecords.add(buildSpotOrderRecord(plan, TRADE_PHASE_CLOSE, "SELL", spotAttempt));
            orderRecords.add(buildFuturesOrderRecord(plan, TRADE_PHASE_CLOSE, "BUY", POSITION_SIDE_SHORT, futuresAttempt));

            if (spotAttempt.isSuccess() && futuresAttempt.isSuccess()) {
                return SingleExchangeArbitrageExecutionResult.builder()
                        .success(true)
                        .symbol(plan.getSymbol())
                        .quantity(quantity.toPlainString())
                        .spotOrderId(String.valueOf(spotAttempt.getOrder().getOrderId()))
                        .futuresOrderId(String.valueOf(futuresAttempt.getOrder().getOrderId()))
                        .message("Success")
                        .orderRecords(orderRecords)
                        .slippageAlerts(collectSlippageAlerts(plan, orderRecords))
                        .build();
            }
            List<SingleExchangeNakedPositionAlert> nakedAlerts = new ArrayList<>();
            if (spotAttempt.isSuccess()) {
                log.error("Legging Risk on close: Spot close succeeded but Futures close failed. Re-opening Spot.");
                OrderAttempt<BinanceOrder> rollbackAttempt = placeSpotOrder(symbol, "BUY", quantity,
                        "CRITICAL: Failed to rollback Spot BUY order!");
                orderRecords.add(buildSpotOrderRecord(plan, TRADE_PHASE_ROLLBACK, "BUY", rollbackAttempt));
                if (!rollbackAttempt.isSuccess()) {
                    nakedAlerts.add(buildNakedPositionAlert(plan.getSymbol(), HEDGE_SIDE_FUTURES, POSITION_SIDE_SHORT,
                            quantity, TRADE_PHASE_CLOSE, rollbackAttempt.getErrorMessage()));
                }
            }
            if (futuresAttempt.isSuccess()) {
                log.error("Legging Risk on close: Futures close succeeded but Spot close failed. Re-opening Futures short.");
                OrderAttempt<BinanceFuturesOrder> rollbackAttempt = openFuturesShort(futuresSymbol, quantity,
                        "CRITICAL: Failed to rollback close flow on futures side!");
                orderRecords.add(buildFuturesOrderRecord(plan, TRADE_PHASE_ROLLBACK, "SELL", POSITION_SIDE_SHORT, rollbackAttempt));
                if (!rollbackAttempt.isSuccess()) {
                    nakedAlerts.add(buildNakedPositionAlert(plan.getSymbol(), HEDGE_SIDE_SPOT, POSITION_SIDE_LONG,
                            quantity, TRADE_PHASE_CLOSE, rollbackAttempt.getErrorMessage()));
                }
            }
            String closeMessage = nakedAlerts.isEmpty()
                    ? "Close arbitrage position failed."
                    : "Close arbitrage position failed. Rollback FAILED - naked position(s) remain.";
            return SingleExchangeArbitrageExecutionResult.builder()
                    .success(false)
                    .symbol(plan.getSymbol())
                    .quantity(quantity.toPlainString())
                    .message(closeMessage)
                    .orderRecords(orderRecords)
                    .slippageAlerts(collectSlippageAlerts(plan, orderRecords))
                    .nakedPositionAlerts(nakedAlerts)
                    .build();
        } catch (Exception e) {
            log.error("Error closing dual-side order", e);
            return SingleExchangeArbitrageExecutionResult.builder()
                    .success(false)
                    .symbol(plan.getSymbol())
                    .quantity(quantity.toPlainString())
                    .message("Exception during close execution: " + e.getMessage())
                    .build();
        }
    }

    private OrderAttempt<BinanceFuturesOrder> closeFuturesShort(String symbol, BigDecimal quantity, String errorLogPrefix) {
        try {
            if (isBinanceTestMode()) {
                binanceApiService.testFuturesOrder(symbol, "BUY", POSITION_SIDE_SHORT, ORDER_TYPE_MARKET,
                        null, quantity.toPlainString(), null, null);
                return OrderAttempt.success(buildSimulatedFuturesOrder(symbol, "BUY", POSITION_SIDE_SHORT, quantity));
            }
            return OrderAttempt.success(binanceApiService.closeShort(symbol, ORDER_TYPE_MARKET, quantity.toPlainString(), null));
        } catch (Exception e) {
            log.error(errorLogPrefix, e);
            return OrderAttempt.failure(e.getMessage());
        }
    }

    private boolean isBinanceTestMode() {
        return properties != null
                && ORDER_EXECUTION_MODE_BINANCE_TEST.equalsIgnoreCase(properties.getOrderExecutionMode());
    }

    private BinanceOrder buildSimulatedSpotOrder(String symbol, String side, BigDecimal quantity) {
        long now = System.currentTimeMillis();
        BigDecimal price = safeSpotPrice(symbol);
        BigDecimal quoteQty = price.multiply(quantity).setScale(INTERNAL_SCALE, RoundingMode.HALF_UP);
        BinanceOrder.Fill fill = new BinanceOrder.Fill(price, quantity, BigDecimal.ZERO, "USDT", now);
        return BinanceOrder.builder()
                .symbol(symbol)
                .orderId(generateSimulatedOrderId())
                .clientOrderId(generateSimulatedClientOrderId())
                .price(BigDecimal.ZERO)
                .origQty(quantity)
                .executedQty(quantity)
                .cummulativeQuoteQty(quoteQty)
                .status(STATUS_FILLED)
                .type(ORDER_TYPE_MARKET)
                .side(side)
                .time(now)
                .updateTime(now)
                .transactTime(now)
                .fills(List.of(fill))
                .build();
    }

    private BinanceFuturesOrder buildSimulatedFuturesOrder(String symbol, String side, String positionSide, BigDecimal quantity) {
        long now = System.currentTimeMillis();
        BigDecimal price = safeFuturesPrice(symbol);
        return BinanceFuturesOrder.builder()
                .symbol(symbol)
                .orderId(generateSimulatedOrderId())
                .clientOrderId(generateSimulatedClientOrderId())
                .price(BigDecimal.ZERO)
                .origQty(quantity)
                .executedQty(quantity)
                .cumQty(quantity)
                .cumQuote(price.multiply(quantity).setScale(INTERNAL_SCALE, RoundingMode.HALF_UP))
                .avgPrice(price)
                .side(side)
                .positionSide(positionSide)
                .status(STATUS_FILLED)
                .type(ORDER_TYPE_MARKET)
                .origType(ORDER_TYPE_MARKET)
                .reduceOnly(false)
                .updateTime(now)
                .build();
    }

    private BigDecimal safeSpotPrice(String symbol) {
        try {
            return getSpotPrice(symbol);
        } catch (Exception e) {
            log.warn("Failed to fetch spot price for simulated order {}, fallback to 0", symbol, e);
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal safeFuturesPrice(String symbol) {
        try {
            return getFuturesPrice(symbol);
        } catch (Exception e) {
            log.warn("Failed to fetch futures price for simulated order {}, fallback to 0", symbol, e);
            return BigDecimal.ZERO;
        }
    }

    private Long generateSimulatedOrderId() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits());
    }

    private String generateSimulatedClientOrderId() {
        return "BINANCE_TEST_" + UUID.randomUUID().toString().replace("-", "");
    }

    private BigDecimal requireQuantity(SingleExchangeSpotFuturesOrderPlan plan) {
        if (plan == null || plan.getQuantity() == null || plan.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("plan quantity must be greater than 0");
        }
        return plan.getQuantity();
    }

    private SingleExchangeArbitrageOrder buildSpotOrderRecord(SingleExchangeSpotFuturesOrderPlan plan,
                                                              String tradePhase,
                                                              String side,
                                                              OrderAttempt<BinanceOrder> attempt) {
        BinanceOrder order = attempt.getOrder();
        return new SingleExchangeArbitrageOrder()
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(plan.getSpotSymbol())
                .setMarketType(MARKET_TYPE_SPOT)
                .setTradePhase(tradePhase)
                .setHedgeSide(HEDGE_SIDE_SPOT)
                .setSide(side)
                .setOrderType(ORDER_TYPE_MARKET)
                .setStatus(order != null && order.getStatus() != null ? order.getStatus() : STATUS_FAILED)
                .setExchangeOrderId(order != null && order.getOrderId() != null ? String.valueOf(order.getOrderId()) : null)
                .setClientOrderId(order != null ? order.getClientOrderId() : null)
                .setRequestedQty(plan.getQuantity())
                .setExecutedQty(order != null && order.getExecutedQty() != null ? order.getExecutedQty() : BigDecimal.ZERO)
                .setRequestedPrice(order != null ? order.getPrice() : null)
                .setAvgPrice(extractSpotAvgPrice(order))
                .setQuoteQty(order != null ? order.getCummulativeQuoteQty() : BigDecimal.ZERO)
                .setFeeAmount(extractSpotFeeAmount(order))
                .setFeeAsset(extractSpotFeeAsset(order))
                .setPlacedAt(extractSpotPlacedAt(order))
                .setFilledAt(extractSpotFilledAt(order))
                .setErrorMessage(attempt.getErrorMessage())
                .setRawResponse(order != null ? decorateRawResponse(order) : null);
    }

    private SingleExchangeArbitrageOrder buildFuturesOrderRecord(SingleExchangeSpotFuturesOrderPlan plan,
                                                                 String tradePhase,
                                                                 String side,
                                                                 String positionSide,
                                                                 OrderAttempt<BinanceFuturesOrder> attempt) {
        BinanceFuturesOrder order = attempt.getOrder();
        return new SingleExchangeArbitrageOrder()
                .setExchangeName(EXCHANGE_NAME)
                .setSymbol(plan.getFuturesSymbol())
                .setMarketType(MARKET_TYPE_FUTURES)
                .setTradePhase(tradePhase)
                .setHedgeSide(HEDGE_SIDE_FUTURES)
                .setSide(side)
                .setPositionSide(order != null && order.getPositionSide() != null ? order.getPositionSide() : positionSide)
                .setOrderType(ORDER_TYPE_MARKET)
                .setStatus(order != null && order.getStatus() != null ? order.getStatus() : STATUS_FAILED)
                .setExchangeOrderId(order != null && order.getOrderId() != null ? String.valueOf(order.getOrderId()) : null)
                .setClientOrderId(order != null ? order.getClientOrderId() : null)
                .setRequestedQty(plan.getQuantity())
                .setExecutedQty(order != null && order.getExecutedQty() != null ? order.getExecutedQty() : BigDecimal.ZERO)
                .setRequestedPrice(order != null ? order.getPrice() : null)
                .setAvgPrice(order != null ? order.getAvgPrice() : null)
                .setQuoteQty(order != null ? order.getCumQuote() : BigDecimal.ZERO)
                .setFeeAmount(BigDecimal.ZERO)
                .setPlacedAt(extractFuturesPlacedAt(order))
                .setFilledAt(extractFuturesFilledAt(order))
                .setErrorMessage(attempt.getErrorMessage())
                .setRawResponse(order != null ? decorateRawResponse(order) : null);
    }

    private String decorateRawResponse(Object order) {
        String raw = String.valueOf(order);
        return isBinanceTestMode() ? "BINANCE_TEST simulated fill after /order/test validation: " + raw : raw;
    }

    /**
     * 成交后真实滑点回填 + 超阈告警收集（P0-3）。
     *
     * <p>遍历已构建的订单记录，对每条已成交腿用真实成交均价与计划参考价计算实际滑点，
     * 超过配置阈值时产出告警记录并落结构化日志；参考价按对冲腿区分现货/合约。
     */
    /**
     * 分腿回滚失败 → 构建裸露仓位告警并落 CRITICAL 日志（P1）。
     *
     * <p>仅当已成交腿的反向 rollback 本身也失败时调用：此刻已成交腿的方向性敞口未被对冲、
     * 残留成一个裸仓，须结构化上报供上层熔断 / 人工介入 / 事后对账，不能只靠一条误导性
     * "Rolled back" 日志掩盖。
     */
    private SingleExchangeNakedPositionAlert buildNakedPositionAlert(String symbol,
                                                                     String nakedHedgeSide,
                                                                     String nakedPositionSide,
                                                                     BigDecimal quantity,
                                                                     String tradePhase,
                                                                     String rollbackErrorMessage) {
        log.error("NAKED POSITION: {} {} {} rollback FAILED at {} phase, quantity={}, rollbackError={}",
                symbol, nakedHedgeSide, nakedPositionSide, tradePhase, quantity, rollbackErrorMessage);
        return SingleExchangeNakedPositionAlert.builder()
                .symbol(symbol)
                .nakedHedgeSide(nakedHedgeSide)
                .nakedPositionSide(nakedPositionSide)
                .quantity(quantity)
                .tradePhase(tradePhase)
                .rollbackErrorMessage(rollbackErrorMessage)
                .build();
    }

    private List<SingleExchangeSlippageAlert> collectSlippageAlerts(SingleExchangeSpotFuturesOrderPlan plan,
                                                                    List<SingleExchangeArbitrageOrder> orderRecords) {
        List<SingleExchangeSlippageAlert> alerts = new ArrayList<>();
        if (orderRecords == null) {
            return alerts;
        }
        for (SingleExchangeArbitrageOrder record : orderRecords) {
            BigDecimal referencePrice = HEDGE_SIDE_FUTURES.equals(record.getHedgeSide())
                    ? plan.getFuturesPrice()
                    : plan.getSpotPrice();
            SingleExchangeSlippageAlert alert = evaluateFillSlippage(record, referencePrice);
            if (alert != null) {
                alerts.add(alert);
            }
        }
        return alerts;
    }

    /**
     * 单腿成交后实际滑点评估。
     *
     * <p>实际滑点 = |avgPrice − referencePrice| / referencePrice；仅当超过配置阈值
     * {@code estimatedSlippageRate} 时返回告警并落结构化 WARN 日志，否则返回 null。
     *
     * <p>Fail-open（有意）：非已成交、成交均价或参考价缺失/非正、阈值未配置时不产出告警——
     * 简化构造入口（openArbitragePosition(symbol, futuresSymbol, quantity)）不回填参考价，
     * 此时无可靠基准，产出告警会制造假阳性，故跳过并留 debug 日志，而非 fail-closed 阻断。
     */
    private SingleExchangeSlippageAlert evaluateFillSlippage(SingleExchangeArbitrageOrder record, BigDecimal referencePrice) {
        if (record == null || !STATUS_FILLED.equals(record.getStatus())) {
            return null;
        }
        BigDecimal avgPrice = record.getAvgPrice();
        if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) <= 0
                || referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug("Slippage check skipped (missing reference/avgPrice): symbol={}, hedgeSide={}, phase={}, referencePrice={}, avgPrice={}",
                    record.getSymbol(), record.getHedgeSide(), record.getTradePhase(), referencePrice, avgPrice);
            return null;
        }
        BigDecimal threshold = properties.getEstimatedSlippageRate();
        BigDecimal actualSlippageRate = avgPrice.subtract(referencePrice).abs()
                .divide(referencePrice, INTERNAL_SCALE, RoundingMode.HALF_UP);
        if (threshold == null || actualSlippageRate.compareTo(threshold) <= 0) {
            return null;
        }
        log.warn("Slippage Alert: {} {} leg {} phase actual slippage {} exceeds threshold {} (referencePrice={}, avgPrice={})",
                record.getSymbol(), record.getHedgeSide(), record.getTradePhase(),
                actualSlippageRate, threshold, referencePrice, avgPrice);
        return SingleExchangeSlippageAlert.builder()
                .symbol(record.getSymbol())
                .hedgeSide(record.getHedgeSide())
                .tradePhase(record.getTradePhase())
                .side(record.getSide())
                .referencePrice(referencePrice)
                .avgPrice(avgPrice)
                .actualSlippageRate(actualSlippageRate)
                .thresholdRate(threshold)
                .build();
    }

    private BigDecimal extractSpotAvgPrice(BinanceOrder order) {
        if (order == null) {
            return null;
        }
        if (order.getFills() != null && !order.getFills().isEmpty()) {
            BigDecimal totalQty = BigDecimal.ZERO;
            BigDecimal totalQuote = BigDecimal.ZERO;
            for (BinanceOrder.Fill fill : order.getFills()) {
                BigDecimal fillQty = fill.getQty() == null ? BigDecimal.ZERO : fill.getQty();
                BigDecimal fillPrice = fill.getPrice() == null ? BigDecimal.ZERO : fill.getPrice();
                totalQty = totalQty.add(fillQty);
                totalQuote = totalQuote.add(fillQty.multiply(fillPrice));
            }
            if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
                return totalQuote.divide(totalQty, INTERNAL_SCALE, RoundingMode.HALF_UP);
            }
        }
        if (order.getExecutedQty() != null && order.getExecutedQty().compareTo(BigDecimal.ZERO) > 0
                && order.getCummulativeQuoteQty() != null) {
            return order.getCummulativeQuoteQty().divide(order.getExecutedQty(), INTERNAL_SCALE, RoundingMode.HALF_UP);
        }
        return order.getPrice();
    }

    private BigDecimal extractSpotFeeAmount(BinanceOrder order) {
        if (order == null || order.getFills() == null) {
            return BigDecimal.ZERO;
        }
        return order.getFills().stream()
                .map(BinanceOrder.Fill::getCommission)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String extractSpotFeeAsset(BinanceOrder order) {
        if (order == null || order.getFills() == null || order.getFills().isEmpty()) {
            return null;
        }
        return order.getFills().stream()
                .map(BinanceOrder.Fill::getCommissionAsset)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Date extractSpotPlacedAt(BinanceOrder order) {
        if (order == null) {
            return null;
        }
        Long timestamp = order.getTransactTime() != null ? order.getTransactTime()
                : order.getTime() != null ? order.getTime()
                : order.getUpdateTime();
        return timestamp == null ? null : new Date(timestamp);
    }

    private Date extractSpotFilledAt(BinanceOrder order) {
        if (order == null || order.getExecutedQty() == null || order.getExecutedQty().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        Long timestamp = order.getUpdateTime() != null ? order.getUpdateTime()
                : order.getTransactTime() != null ? order.getTransactTime()
                : order.getTime();
        return timestamp == null ? null : new Date(timestamp);
    }

    private Date extractFuturesPlacedAt(BinanceFuturesOrder order) {
        if (order == null || order.getUpdateTime() == null) {
            return null;
        }
        return new Date(order.getUpdateTime());
    }

    private Date extractFuturesFilledAt(BinanceFuturesOrder order) {
        if (order == null || order.getExecutedQty() == null || order.getExecutedQty().compareTo(BigDecimal.ZERO) <= 0
                || order.getUpdateTime() == null) {
            return null;
        }
        return new Date(order.getUpdateTime());
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
        BinancePriceTicker ticker = getQueryDataProvider().getSpotPrice(symbol);
        if (ticker == null || ticker.getPrice() == null) {
            throw new IllegalArgumentException("Spot price not available for " + symbol);
        }
        return ticker.getPrice();
    }

    private BigDecimal getFuturesPrice(String symbol) {
        BinancePriceTicker ticker = getQueryDataProvider().getFuturesPrice(symbol);
        if (ticker == null || ticker.getPrice() == null) {
            throw new IllegalArgumentException("Futures price not available for " + symbol);
        }
        return ticker.getPrice();
    }

    private BinanceQueryDataProvider getQueryDataProvider() {
        if (queryDataProvider != null) {
            return queryDataProvider;
        }
        return new com.metaverse.coin.strategy.core.arbitrage.market.HttpBinanceQueryDataProvider(binanceApiService);
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

    private static final class OrderAttempt<T> {
        private final T order;
        private final String errorMessage;

        private OrderAttempt(T order, String errorMessage) {
            this.order = order;
            this.errorMessage = errorMessage;
        }

        private static <T> OrderAttempt<T> success(T order) {
            return new OrderAttempt<>(order, null);
        }

        private static <T> OrderAttempt<T> failure(String errorMessage) {
            return new OrderAttempt<>(null, errorMessage);
        }

        private T getOrder() {
            return order;
        }

        private String getErrorMessage() {
            return errorMessage;
        }

        private boolean isSuccess() {
            return order != null;
        }
    }
}
