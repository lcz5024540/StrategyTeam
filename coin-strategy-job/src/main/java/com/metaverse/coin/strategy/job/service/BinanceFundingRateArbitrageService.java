package com.metaverse.coin.strategy.job.service;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.arbitrage.execution.ArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.OrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.risk.RiskController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Binance funding-rate hedge executor.
 * Opens delta-neutral positions: Long Spot + Short Futures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class BinanceFundingRateArbitrageService {

    private final BinanceApiService binanceApiService;
    private final OrderExecutor orderExecutor;
    private final RiskController riskController;

    public String executeArbitrage(String symbol, BigDecimal usdtAmount, BigDecimal minFundingRate) {
        return executeArbitrage(parseSymbols(symbol), usdtAmount, minFundingRate, 3);
    }

    public String executeArbitrage(String symbols, BigDecimal usdtAmount, BigDecimal minFundingRate, Integer leverage) {
        return executeArbitrage(parseSymbols(symbols), usdtAmount, minFundingRate, leverage);
    }

    public String executeArbitrage(List<String> symbols, BigDecimal usdtAmount, BigDecimal minFundingRate, Integer leverage) {
        StringBuilder resultLog = new StringBuilder();
        List<String> normalizedSymbols = normalizeSymbols(symbols);
        int normalizedLeverage = leverage == null || leverage <= 0 ? 3 : leverage;

        if (normalizedSymbols.isEmpty()) {
            return "No symbols provided.";
        }

        logAndAppend(resultLog, "=== Starting Funding Rate Arbitrage ===");
        logAndAppend(resultLog, "Symbols: " + String.join(", ", normalizedSymbols));
        logAndAppend(resultLog, "Amount Per Symbol: " + usdtAmount + " USDT");
        logAndAppend(resultLog, "Min Funding Rate: " + minFundingRate);
        logAndAppend(resultLog, "Leverage: " + normalizedLeverage + "x");

        Map<String, String> executionSummary = new LinkedHashMap<>();
        for (String symbol : normalizedSymbols) {
            try {
                logAndAppend(resultLog, "");
                logAndAppend(resultLog, ">> Evaluating " + symbol);

                BinanceFuturesPremiumIndex premiumIndex = getFundingRate(symbol);
                if (premiumIndex == null || premiumIndex.getLastFundingRate() == null) {
                    executionSummary.put(symbol, "Skipped: funding rate unavailable");
                    logAndAppend(resultLog, "Skipped: funding rate unavailable");
                    continue;
                }

                BigDecimal fundingRate = premiumIndex.getLastFundingRate();
                logAndAppend(resultLog, "Funding Rate: " + fundingRate);

                if (fundingRate.compareTo(minFundingRate) < 0) {
                    executionSummary.put(symbol, "Skipped: funding rate below threshold");
                    logAndAppend(resultLog, "Skipped: funding rate below threshold");
                    continue;
                }

                SpotFuturesOrderPlan plan = orderExecutor.createSpotFuturesPlan(symbol, usdtAmount, normalizedLeverage);
                plan.setFundingRate(fundingRate);
                logAndAppend(resultLog, "Calculated Quantity: " + plan.getQuantity());
                logAndAppend(resultLog, "Spot Price: " + plan.getSpotPrice() + ", Futures Price: " + plan.getFuturesPrice());

                if (plan.getFuturesPrice().subtract(plan.getSpotPrice()).compareTo(BigDecimal.ZERO) < 0) {
                    executionSummary.put(symbol, "Skipped: futures basis is negative");
                    logAndAppend(resultLog, "Skipped: futures basis is negative");
                    continue;
                }

                if (!riskController.checkTradeRisk(plan)) {
                    executionSummary.put(symbol, "Rejected: risk check failed");
                    logAndAppend(resultLog, "Rejected: risk check failed");
                    continue;
                }

                prepareFuturesSettings(symbol, normalizedLeverage, resultLog);
                ArbitrageExecutionResult executionResult = orderExecutor.openArbitragePosition(plan);
                executionSummary.put(symbol, executionResult.isSuccess() ? "Opened" : "Failed");
                logAndAppend(resultLog, "Execution Result: " + executionResult.getMessage());
                if (executionResult.isSuccess()) {
                    logAndAppend(resultLog, "Spot Order ID: " + executionResult.getSpotOrderId());
                    logAndAppend(resultLog, "Futures Order ID: " + executionResult.getFuturesOrderId());
                }
            } catch (Exception e) {
                log.error("Failed to execute funding-rate arbitrage for {}", symbol, e);
                executionSummary.put(symbol, "Failed: " + e.getMessage());
                logAndAppend(resultLog, "Failed: " + e.getMessage());
            }
        }

        logAndAppend(resultLog, "");
        logAndAppend(resultLog, "=== Summary ===");
        executionSummary.forEach((symbol, status) -> logAndAppend(resultLog, symbol + " -> " + status));
        return resultLog.toString();
    }

    private void prepareFuturesSettings(String symbol, Integer leverage, StringBuilder logBuffer) {
        try {
            binanceApiService.changeInitialLeverage(symbol, leverage);
            logAndAppend(logBuffer, "Futures leverage set to " + leverage + "x");
        } catch (Exception e) {
            logAndAppend(logBuffer, "Leverage setup warning: " + e.getMessage());
        }
    }

    private BinanceFuturesPremiumIndex getFundingRate(String symbol) {
        return binanceApiService.getFuturesFundingRate(symbol);
    }

    private List<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    private List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null) {
            return new ArrayList<>();
        }
        return symbols.stream()
                .filter(item -> item != null && !item.trim().isEmpty())
                .map(item -> item.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
    }

    private void logAndAppend(StringBuilder buffer, String msg) {
        log.info(msg);
        buffer.append(msg).append("\n");
    }
}
