package com.metaverse.coin.strategy.core.arbitrage.decision;

import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.Strategy;
import com.metaverse.coin.strategy.core.arbitrage.execution.ArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.OrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.monitor.FundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.RiskController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spot-Futures Arbitrage Strategy (Single Exchange)
 * Strategy Logic:
 * 1. Monitor Funding Rate.
 * 2. If Rate > Open Threshold -> Open Long Spot + Short Futures.
 * 3. If Rate < Close Threshold -> Close Positions.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SpotFuturesArbitrageStrategy implements Strategy {

    private final FundingRateMonitor fundingRateMonitor;
    private final OrderExecutor orderExecutor;
    private final RiskController riskController;
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Configuration (TODO: Move to Config Service)
    private static final BigDecimal OPEN_THRESHOLD = new BigDecimal("0.0005"); // 0.05%
    private static final BigDecimal CLOSE_THRESHOLD = new BigDecimal("0.0001"); // 0.01%
    private static final BigDecimal TRADE_QUANTITY = new BigDecimal("0.001"); // Example BTC amount
    private static final String SYMBOL = "BTCUSDT"; // Example Target

    // State
    private final Map<String, Boolean> activePositions = new ConcurrentHashMap<>();

    @Override
    public String getStrategyId() {
        return "SPOT_FUTURES_ARBITRAGE_V1";
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Spot-Futures Arbitrage Strategy...");
            // Schedule if not already scheduled or use a task that checks the flag
            // For simplicity, we assume this is called once at startup
            scheduler.scheduleAtFixedRate(this::executeLogic, 0, 1, TimeUnit.MINUTES);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Spot-Futures Arbitrage Strategy...");
            // We don't shutdown scheduler to allow restart, but the task will just return immediately
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void executeLogic() {
        if (!running.get()) return;

        try {
            log.info("Checking arbitrage opportunities for {}", SYMBOL);
            
            // 1. Get Data
            BinanceFuturesPremiumIndex premiumIndex = fundingRateMonitor.getBinanceFundingRate(SYMBOL);
            if (premiumIndex == null) return;

            BigDecimal currentRate = premiumIndex.getLastFundingRate();
            log.info("Current Funding Rate for {}: {}", SYMBOL, currentRate);

            boolean hasPosition = activePositions.getOrDefault(SYMBOL, false);

            // 2. Decision
            if (!hasPosition) {
                // Check Open Condition
                if (currentRate != null && currentRate.compareTo(OPEN_THRESHOLD) > 0) {
                    
                    // Risk Check
                    if (!riskController.checkTradeRisk(SYMBOL, TRADE_QUANTITY, premiumIndex.getMarkPrice())) {
                        log.warn("Trade rejected by Risk Controller");
                        return;
                    }

                    log.info("Funding Rate {} > Threshold {}. Opening Position...", currentRate, OPEN_THRESHOLD);
                    ArbitrageExecutionResult result = orderExecutor.openArbitragePosition(SYMBOL, SYMBOL, TRADE_QUANTITY);
                    if (result.isSuccess()) {
                        activePositions.put(SYMBOL, true);
                    }
                }
            } else {
                // Check Close Condition
                if (currentRate != null && currentRate.compareTo(CLOSE_THRESHOLD) < 0) {
                    log.info("Funding Rate {} < Threshold {}. Closing Position...", currentRate, CLOSE_THRESHOLD);
                    boolean success = orderExecutor.closeArbitragePosition(SYMBOL, SYMBOL, TRADE_QUANTITY);
                    if (success) {
                        activePositions.put(SYMBOL, false);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error in strategy execution loop", e);
        }
    }
}
