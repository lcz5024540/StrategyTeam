package com.metaverse.coin.strategy.core.arbitrage.risk;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.market.BinanceOrderBook;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo.BinanceBalance;
import com.metaverse.coin.strategy.core.arbitrage.config.SingleExchangeSpotFuturesProperties;
import com.metaverse.coin.strategy.core.arbitrage.execution.SingleExchangeSpotFuturesOrderPlan;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeDepthMonitor;
import com.metaverse.coin.strategy.core.arbitrage.monitor.SingleExchangeDepthSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Risk Controller
 * Handles pre-trade risk checks and account health monitoring.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SingleExchangeRiskController {

    public static final String PHASE_OPEN = "OPEN";
    public static final String PHASE_CLOSE = "CLOSE";

    private final BinanceApiService binanceApiService;
    private final SingleExchangeDepthMonitor depthMonitor;
    private final SingleExchangeSpotFuturesProperties properties;

    /**
     * Check if a new trade is allowed.
     */
    public boolean checkTradeRisk(String symbol, BigDecimal quantity, BigDecimal price) {
        SingleExchangeSpotFuturesOrderPlan plan = SingleExchangeSpotFuturesOrderPlan.builder()
                .symbol(symbol)
                .spotSymbol(symbol)
                .futuresSymbol(symbol)
                .quantity(quantity)
                .spotPrice(price)
                .futuresPrice(price)
                .spotNotional(quantity.multiply(price))
                .futuresNotional(quantity.multiply(price))
                .leverage(1)
                .build();
        return checkTradeRisk(plan);
    }

    public boolean checkTradeRisk(SingleExchangeSpotFuturesOrderPlan plan) {
        if (plan == null || plan.getQuantity() == null || plan.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Risk Alert: invalid trade plan");
            return false;
        }

        // 1. Check Notional Value
        BigDecimal tradeValue = plan.getSpotNotional() != null
                ? plan.getSpotNotional()
                : plan.getQuantity().multiply(plan.getSpotPrice());
        
        if (tradeValue.compareTo(properties.getMaxPositionValueUsdt()) > 0) {
            log.warn("Risk Alert: Trade value {} exceeds max allowed {}", tradeValue, properties.getMaxPositionValueUsdt());
            return false;
        }

        // 2. Check Spot Balance (Assuming USDT for buying)
        try {
            BinanceAccountInfo spotAccount = binanceApiService.account();
            Optional<BinanceBalance> usdtBalance = spotAccount.getBalances().stream()
                    .filter(b -> "USDT".equals(b.getAsset()))
                    .findFirst();

            if (usdtBalance.isPresent()) {
                BigDecimal free = usdtBalance.get().getFree();
                if (free.compareTo(tradeValue) < 0) {
                    log.warn("Risk Alert: Insufficient Spot USDT Balance. Required: {}, Available: {}", tradeValue, free);
                    return false;
                }
            } else {
                log.warn("Risk Alert: USDT balance not found in Spot account");
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to fetch Spot account info during risk check", e);
            return false; // Fail safe
        }

        // 3. Check Futures Balance against expected initial margin.
        try {
            BinanceFuturesAccount futuresAccount = binanceApiService.getFuturesAccount();
            BigDecimal availableBalance = futuresAccount.getAssets().stream()
                    .filter(asset -> "USDT".equals(asset.getAsset()))
                    .findFirst()
                    .map(BinanceFuturesAccount.BinanceFuturesAsset::getAvailableBalance)
                    .orElse(BigDecimal.ZERO);
            BigDecimal requiredMargin = plan.getRequiredFuturesMargin();
            BigDecimal bufferedRequiredMargin = requiredMargin.multiply(properties.getRequiredMarginBufferRate());

            if (availableBalance.compareTo(bufferedRequiredMargin) < 0) {
                log.warn("Risk Alert: Insufficient Futures Balance. Required buffered margin: {}, Available: {}", bufferedRequiredMargin, availableBalance);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to fetch Futures account info during risk check", e);
            return false;
        }

        // 4. Check Futures Margin Health
        if (!checkAccountHealth()) {
            log.warn("Risk Alert: Futures Account Health is poor");
            return false;
        }
        
        return true;
    }

    public SingleExchangeLiquidityAssessment evaluateLiquidityRisk(SingleExchangeSpotFuturesOrderPlan plan, String phase) {
        BigDecimal configuredSlippageRate = defaultValue(properties.getEstimatedSlippageRate());
        SingleExchangeLiquidityAssessment.SingleExchangeLiquidityAssessmentBuilder builder = SingleExchangeLiquidityAssessment.builder()
                .phase(phase)
                .estimatedSlippageRate(configuredSlippageRate)
                .spotTopLevelEnough(true)
                .futuresTopLevelEnough(true);

        if (!Boolean.TRUE.equals(properties.getLiquidityCheckEnabled())) {
            return builder.passed(true).build();
        }

        if (plan == null || plan.getQuantity() == null || plan.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            return builder
                    .passed(false)
                    .rejectionReason("invalid trade plan")
                    .spotTopLevelEnough(false)
                    .futuresTopLevelEnough(false)
                    .build();
        }

        String tradeSymbol = plan.getSymbol() != null ? plan.getSymbol() : plan.getSpotSymbol();
        SingleExchangeDepthSnapshot snapshot = depthMonitor.getDepthSnapshot(tradeSymbol);
        if (snapshot == null || snapshot.isFallbackUsed()) {
            return builder
                    .passed(false)
                    .fallbackUsed(true)
                    .rejectionReason("depth data unavailable after retries")
                    .build();
        }

        BinanceOrderBook spotOrderBook = snapshot.getSpotOrderBook();
        BinanceFuturesOrderBook futuresOrderBook = snapshot.getFuturesOrderBook();
        BigDecimal quantity = plan.getQuantity();

        VwapEstimate spotEstimate = estimateVwap(resolveSpotLevels(phase, spotOrderBook), quantity);
        VwapEstimate futuresEstimate = estimateVwap(resolveFuturesLevels(phase, futuresOrderBook), quantity);
        builder.spotExpectedPrice(spotEstimate.vwap())
                .futuresExpectedPrice(futuresEstimate.vwap());

        builder.spotTopLevelEnough(spotEstimate.sufficient())
                .futuresTopLevelEnough(futuresEstimate.sufficient());
        if (!spotEstimate.sufficient() || !futuresEstimate.sufficient()) {
            return builder
                    .passed(false)
                    .rejectionReason(buildDepthRejectionReason(
                            phase,
                            quantity,
                            spotEstimate.availableQty(),
                            futuresEstimate.availableQty()
                    ))
                    .build();
        }

        BigDecimal spotMid = calculateMidPrice(
                extractLevelPrice(spotOrderBook.getBids(), 0),
                extractLevelPrice(spotOrderBook.getAsks(), 0)
        );
        BigDecimal futuresMid = calculateMidPrice(
                extractLevelPrice(futuresOrderBook.getBids(), 0),
                extractLevelPrice(futuresOrderBook.getAsks(), 0)
        );
        if (!isPositive(spotMid) || !isPositive(futuresMid) || !isPositive(spotEstimate.vwap()) || !isPositive(futuresEstimate.vwap())) {
            return builder
                    .passed(false)
                    .rejectionReason("mid price or expected execution price is unavailable")
                    .build();
        }

        BigDecimal spotLegSlippageRate = spotEstimate.vwap().subtract(spotMid).abs()
                .divide(spotMid, 8, RoundingMode.HALF_UP);
        BigDecimal futuresLegSlippageRate = futuresEstimate.vwap().subtract(futuresMid).abs()
                .divide(futuresMid, 8, RoundingMode.HALF_UP);
        BigDecimal estimatedSlippageRate = spotLegSlippageRate.add(futuresLegSlippageRate);
        builder.estimatedSlippageRate(estimatedSlippageRate);

        if (estimatedSlippageRate.compareTo(properties.getEstimatedSlippageRate()) > 0) {
            return builder
                    .passed(false)
                    .rejectionReason("estimated slippage " + estimatedSlippageRate + " exceeds threshold " + properties.getEstimatedSlippageRate())
                    .build();
        }

        return builder.passed(true).build();
    }

    /**
     * Check account health (e.g., Margin Ratio).
     * Margin Ratio = Margin Balance / Maintenance Margin
     */
    public boolean checkAccountHealth() {
        BigDecimal marginRatio = getMarginRatio();
        if (marginRatio == null) {
            return false;
        }
        if (marginRatio.compareTo(properties.getMinMarginRatio()) < 0) {
            log.warn("Risk Alert: Low Margin Ratio: {}", marginRatio);
            return false;
        }
        return true;
    }

    public BigDecimal getMarginRatio() {
        try {
            BinanceFuturesAccount futuresAccount = binanceApiService.getFuturesService().account();
            BigDecimal marginBalance = futuresAccount.getTotalMarginBalance();
            BigDecimal maintMargin = futuresAccount.getTotalMaintMargin();

            // If no positions (maintMargin is 0), health is perfect
            if (maintMargin.compareTo(BigDecimal.ZERO) == 0) {
                return new BigDecimal("999");
            }

            BigDecimal marginRatio = marginBalance.divide(maintMargin, 2, RoundingMode.HALF_DOWN);
            log.info("Current Futures Margin Ratio: {}", marginRatio);
            return marginRatio;
        } catch (Exception e) {
            log.error("Failed to check Futures account health", e);
            return null; // Fail safe
        }
    }

    private List<List<String>> resolveSpotLevels(String phase, BinanceOrderBook orderBook) {
        if (orderBook == null) {
            return null;
        }
        return PHASE_CLOSE.equalsIgnoreCase(phase) ? orderBook.getBids() : orderBook.getAsks();
    }

    private List<List<String>> resolveFuturesLevels(String phase, BinanceFuturesOrderBook orderBook) {
        if (orderBook == null) {
            return null;
        }
        return PHASE_CLOSE.equalsIgnoreCase(phase) ? orderBook.getAsks() : orderBook.getBids();
    }

    private VwapEstimate estimateVwap(List<List<String>> levels, BigDecimal quantity) {
        if (levels == null || levels.isEmpty() || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return new VwapEstimate(null, BigDecimal.ZERO, false);
        }

        BigDecimal remainingQty = quantity;
        BigDecimal filledQty = BigDecimal.ZERO;
        BigDecimal totalQuote = BigDecimal.ZERO;
        BigDecimal availableQty = BigDecimal.ZERO;

        for (List<String> level : levels) {
            if (level == null || level.size() < 2) {
                continue;
            }
            BigDecimal levelPrice = parseDecimal(level.get(0));
            BigDecimal levelQty = parseDecimal(level.get(1));
            if (!isPositive(levelPrice) || !isPositive(levelQty)) {
                continue;
            }

            availableQty = availableQty.add(levelQty);
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal executedQty = remainingQty.min(levelQty);
            totalQuote = totalQuote.add(executedQty.multiply(levelPrice));
            filledQty = filledQty.add(executedQty);
            remainingQty = remainingQty.subtract(executedQty);
        }

        if (filledQty.compareTo(BigDecimal.ZERO) <= 0) {
            return new VwapEstimate(null, availableQty, false);
        }

        return new VwapEstimate(
                totalQuote.divide(filledQty, 8, RoundingMode.HALF_UP),
                availableQty,
                remainingQty.compareTo(BigDecimal.ZERO) <= 0
        );
    }

    private BigDecimal extractLevelPrice(List<List<String>> levels, int index) {
        if (levels == null || levels.size() <= index || levels.get(index) == null || levels.get(index).size() < 1) {
            return null;
        }
        return parseDecimal(levels.get(index).get(0));
    }

    private BigDecimal calculateMidPrice(BigDecimal bidPrice, BigDecimal askPrice) {
        if (!isPositive(bidPrice) || !isPositive(askPrice)) {
            return null;
        }
        return bidPrice.add(askPrice).divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
    }

    private String buildDepthRejectionReason(String phase,
                                             BigDecimal quantity,
                                             BigDecimal spotAvailableQty,
                                             BigDecimal futuresAvailableQty) {
        return "order-book depth is insufficient for " + phase
                + ": required=" + quantity
                + ", spotAvailable=" + defaultValue(spotAvailableQty)
                + ", futuresAvailable=" + defaultValue(futuresAvailableQty);
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal defaultValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    private record VwapEstimate(BigDecimal vwap, BigDecimal availableQty, boolean sufficient) {
    }
}
