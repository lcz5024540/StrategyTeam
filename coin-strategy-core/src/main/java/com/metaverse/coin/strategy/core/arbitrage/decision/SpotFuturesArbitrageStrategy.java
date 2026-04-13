package com.metaverse.coin.strategy.core.arbitrage.decision;

import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesPremiumIndex;
import com.metaverse.coin.strategy.core.Strategy;
import com.metaverse.coin.strategy.core.arbitrage.execution.ArbitrageExecutionResult;
import com.metaverse.coin.strategy.core.arbitrage.execution.OrderExecutor;
import com.metaverse.coin.strategy.core.arbitrage.execution.SpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.monitor.FundingRateMonitor;
import com.metaverse.coin.strategy.core.arbitrage.risk.RiskController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spot-Futures Arbitrage Strategy (Single Exchange)
 * Strategy Logic:
 * 1. Build a real trade plan from current spot/futures prices.
 * 2. Open only when funding, basis and settlement timing all pass filters.
 * 3. Close when funding weakens, basis deteriorates or margin health becomes unsafe.
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

    @Value("${arbitrage.spot-futures.symbol:BTCUSDT}")
    private String symbol = "BTCUSDT";

    @Value("${arbitrage.spot-futures.trade-usdt-amount:100}")
    private BigDecimal tradeUsdtAmount = new BigDecimal("100");

    @Value("${arbitrage.spot-futures.leverage:3}")
    private Integer leverage = 3;

    @Value("${arbitrage.spot-futures.open-threshold:0.0005}")
    private BigDecimal openThreshold = new BigDecimal("0.0005");

    @Value("${arbitrage.spot-futures.close-threshold:0.0001}")
    private BigDecimal closeThreshold = new BigDecimal("0.0001");

    @Value("${arbitrage.spot-futures.min-basis-rate:0.0000}")
    private BigDecimal minBasisRate = BigDecimal.ZERO;

    @Value("${arbitrage.spot-futures.max-basis-rate:0.0030}")
    private BigDecimal maxBasisRate = new BigDecimal("0.0030");

    @Value("${arbitrage.spot-futures.max-basis-deterioration-rate:0.0015}")
    private BigDecimal maxBasisDeteriorationRate = new BigDecimal("0.0015");

    @Value("${arbitrage.spot-futures.estimated-round-trip-fee-rate:0.0010}")
    private BigDecimal estimatedRoundTripFeeRate = new BigDecimal("0.0010");

    @Value("${arbitrage.spot-futures.estimated-slippage-rate:0.0005}")
    private BigDecimal estimatedSlippageRate = new BigDecimal("0.0005");

    @Value("${arbitrage.spot-futures.min-funding-periods:4}")
    private Integer minFundingPeriods = 4;

    @Value("${arbitrage.spot-futures.min-net-yield-rate:0.0002}")
    private BigDecimal minNetYieldRate = new BigDecimal("0.0002");

    @Value("${arbitrage.spot-futures.settlement-protection-window-minutes:5}")
    private long settlementProtectionWindowMinutes = 5L;

    @Value("${arbitrage.spot-futures.emergency-close-margin-ratio:1.80}")
    private BigDecimal emergencyCloseMarginRatio = new BigDecimal("1.80");

    private final Map<String, ActivePosition> activePositions = new ConcurrentHashMap<>();

    @Override
    public String getStrategyId() {
        return "SPOT_FUTURES_ARBITRAGE_V1";
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Spot-Futures Arbitrage Strategy...");
            scheduler.scheduleAtFixedRate(this::executeLogic, 0, 1, TimeUnit.MINUTES);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Spot-Futures Arbitrage Strategy...");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void executeLogic() {
        if (!running.get()) {
            return;
        }

        try {
            log.info("Checking arbitrage opportunities for {}", symbol);

            BinanceFuturesPremiumIndex premiumIndex = fundingRateMonitor.getBinanceFundingRate(symbol);
            if (premiumIndex == null || premiumIndex.getLastFundingRate() == null) {
                log.warn("Funding data unavailable for {}", symbol);
                return;
            }

            BigDecimal currentRate = premiumIndex.getLastFundingRate();
            SpotFuturesOrderPlan marketPlan = orderExecutor.createSpotFuturesPlan(symbol, tradeUsdtAmount, leverage);
            marketPlan.setFundingRate(currentRate);
            log.info(
                    "Opportunity snapshot for {}: fundingRate={}, basisRate={}, basisSpread={}, quantity={}, nextFundingTime={}",
                    symbol,
                    currentRate,
                    marketPlan.getBasisRate(),
                    marketPlan.getBasisSpread(),
                    marketPlan.getQuantity(),
                    premiumIndex.getNextFundingTime()
            );

            ActivePosition activePosition = activePositions.get(symbol);
            if (activePosition == null) {
                String openBlockReason = getOpenBlockReason(premiumIndex, marketPlan);
                if (openBlockReason != null) {
                    log.info("Skip open for {}: {}", symbol, openBlockReason);
                    return;
                }

                if (!riskController.checkTradeRisk(marketPlan)) {
                    log.warn("Trade rejected by Risk Controller for {}", symbol);
                    return;
                }

                log.info(
                        "Opening position for {} with quantity={}, spotNotional={}, futuresNotional={}, leverage={}",
                        symbol,
                        marketPlan.getQuantity(),
                        marketPlan.getSpotNotional(),
                        marketPlan.getFuturesNotional(),
                        marketPlan.getLeverage()
                );
                ArbitrageExecutionResult result = orderExecutor.openArbitragePosition(marketPlan);
                if (result.isSuccess()) {
                    activePositions.put(symbol, ActivePosition.fromPlan(marketPlan, premiumIndex.getNextFundingTime()));
                } else {
                    log.warn("Failed to open arbitrage position for {}: {}", symbol, result.getMessage());
                }
                return;
            }

            String closeReason = getCloseReason(premiumIndex, marketPlan, activePosition);
            if (closeReason == null) {
                return;
            }

            SpotFuturesOrderPlan closePlan = SpotFuturesOrderPlan.builder()
                    .symbol(symbol)
                    .spotSymbol(symbol)
                    .futuresSymbol(symbol)
                    .quantity(activePosition.quantity())
                    .leverage(activePosition.leverage())
                    .build();
            log.info(
                    "Closing position for {} because {}. quantity={}, currentFundingRate={}, currentBasisRate={}, entryBasisRate={}",
                    symbol,
                    closeReason,
                    activePosition.quantity(),
                    currentRate,
                    marketPlan.getBasisRate(),
                    activePosition.entryBasisRate()
            );
            boolean success = orderExecutor.closeArbitragePosition(closePlan);
            if (success) {
                activePositions.remove(symbol);
            } else {
                log.warn("Failed to close arbitrage position for {} after trigger: {}", symbol, closeReason);
            }
        } catch (Exception e) {
            log.error("Error in strategy execution loop", e);
        }
    }

    private String getOpenBlockReason(BinanceFuturesPremiumIndex premiumIndex, SpotFuturesOrderPlan marketPlan) {
        BigDecimal currentRate = defaultValue(premiumIndex.getLastFundingRate());
        if (currentRate.compareTo(openThreshold) <= 0) {
            return "funding rate " + currentRate + " is not above open threshold " + openThreshold;
        }
        if (isWithinSettlementProtectionWindow(premiumIndex.getNextFundingTime())) {
            return "next funding settlement is too close";
        }

        BigDecimal basisRate = defaultValue(marketPlan.getBasisRate());
        if (basisRate.compareTo(minBasisRate) < 0) {
            return "basis rate " + basisRate + " is below minimum " + minBasisRate;
        }
        if (basisRate.compareTo(maxBasisRate) > 0) {
            return "basis rate " + basisRate + " is above maximum " + maxBasisRate;
        }

        BigDecimal expectedNetYield = estimateExpectedNetYield(currentRate);
        if (expectedNetYield.compareTo(minNetYieldRate) < 0) {
            return "expected net yield " + expectedNetYield + " is below minimum " + minNetYieldRate;
        }
        return null;
    }

    private String getCloseReason(BinanceFuturesPremiumIndex premiumIndex, SpotFuturesOrderPlan marketPlan, ActivePosition activePosition) {
        BigDecimal currentRate = defaultValue(premiumIndex.getLastFundingRate());
        if (currentRate.compareTo(BigDecimal.ZERO) <= 0) {
            return "funding rate turned non-positive";
        }
        if (currentRate.compareTo(closeThreshold) < 0) {
            return "funding rate dropped below close threshold";
        }

        BigDecimal currentBasisRate = defaultValue(marketPlan.getBasisRate());
        BigDecimal basisWideningRate = currentBasisRate.subtract(activePosition.entryBasisRate());
        if (basisWideningRate.compareTo(maxBasisDeteriorationRate) > 0) {
            return "basis widened by " + basisWideningRate + ", exceeding " + maxBasisDeteriorationRate;
        }

        BigDecimal marginRatio = riskController.getMarginRatio();
        if (marginRatio != null && marginRatio.compareTo(emergencyCloseMarginRatio) < 0) {
            return "margin ratio " + marginRatio + " is below emergency threshold " + emergencyCloseMarginRatio;
        }
        return null;
    }

    private BigDecimal estimateExpectedNetYield(BigDecimal currentRate) {
        int fundingPeriods = minFundingPeriods == null || minFundingPeriods <= 0 ? 1 : minFundingPeriods;
        return currentRate.multiply(BigDecimal.valueOf(fundingPeriods))
                .subtract(estimatedRoundTripFeeRate)
                .subtract(estimatedSlippageRate)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private boolean isWithinSettlementProtectionWindow(Long nextFundingTime) {
        if (nextFundingTime == null || settlementProtectionWindowMinutes <= 0) {
            return false;
        }
        long millisUntilSettlement = nextFundingTime - System.currentTimeMillis();
        return millisUntilSettlement >= 0
                && millisUntilSettlement <= TimeUnit.MINUTES.toMillis(settlementProtectionWindowMinutes);
    }

    private BigDecimal defaultValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record ActivePosition(
            BigDecimal quantity,
            BigDecimal entryBasisRate,
            BigDecimal entryFundingRate,
            Integer leverage,
            Long nextFundingTime
    ) {
        private static ActivePosition fromPlan(SpotFuturesOrderPlan plan, Long nextFundingTime) {
            return new ActivePosition(
                    plan.getQuantity(),
                    plan.getBasisRate() == null ? BigDecimal.ZERO : plan.getBasisRate(),
                    plan.getFundingRate() == null ? BigDecimal.ZERO : plan.getFundingRate(),
                    plan.getLeverage(),
                    nextFundingTime
            );
        }
    }
}
