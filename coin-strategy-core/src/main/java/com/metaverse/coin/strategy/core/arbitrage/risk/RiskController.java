package com.metaverse.coin.strategy.core.arbitrage.risk;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import com.metaverse.coin.strategy.common.model.binance.entity.futures.BinanceFuturesAccount;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo;
import com.metaverse.coin.strategy.common.model.binance.entity.spot.BinanceAccountInfo.BinanceBalance;
import com.metaverse.coin.strategy.core.arbitrage.execution.SpotFuturesOrderPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Risk Controller
 * Handles pre-trade risk checks and account health monitoring.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RiskController {

    private final BinanceApiService binanceApiService;

    private static final BigDecimal MAX_POSITION_VALUE_USDT = new BigDecimal("1000"); // Safety cap
    private static final BigDecimal MIN_MARGIN_RATIO = new BigDecimal("2.0"); // 200% Margin Ratio for Futures

    /**
     * Check if a new trade is allowed.
     */
    public boolean checkTradeRisk(String symbol, BigDecimal quantity, BigDecimal price) {
        SpotFuturesOrderPlan plan = SpotFuturesOrderPlan.builder()
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

    public boolean checkTradeRisk(SpotFuturesOrderPlan plan) {
        if (plan == null || plan.getQuantity() == null || plan.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Risk Alert: invalid trade plan");
            return false;
        }

        // 1. Check Notional Value
        BigDecimal tradeValue = plan.getSpotNotional() != null
                ? plan.getSpotNotional()
                : plan.getQuantity().multiply(plan.getSpotPrice());
        
        if (tradeValue.compareTo(MAX_POSITION_VALUE_USDT) > 0) {
            log.warn("Risk Alert: Trade value {} exceeds max allowed {}", tradeValue, MAX_POSITION_VALUE_USDT);
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

            if (availableBalance.compareTo(requiredMargin) < 0) {
                log.warn("Risk Alert: Insufficient Futures Balance. Required margin: {}, Available: {}", requiredMargin, availableBalance);
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

    /**
     * Check account health (e.g., Margin Ratio).
     * Margin Ratio = Margin Balance / Maintenance Margin
     */
    public boolean checkAccountHealth() {
        try {
            BinanceFuturesAccount futuresAccount = binanceApiService.getFuturesService().account();
            BigDecimal marginBalance = futuresAccount.getTotalMarginBalance();
            BigDecimal maintMargin = futuresAccount.getTotalMaintMargin();

            // If no positions (maintMargin is 0), health is perfect
            if (maintMargin.compareTo(BigDecimal.ZERO) == 0) {
                return true;
            }

            BigDecimal marginRatio = marginBalance.divide(maintMargin, 2, RoundingMode.HALF_DOWN);
            log.info("Current Futures Margin Ratio: {}", marginRatio);

            if (marginRatio.compareTo(MIN_MARGIN_RATIO) < 0) {
                log.warn("Risk Alert: Low Margin Ratio: {}", marginRatio);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to check Futures account health", e);
            return false; // Fail safe
        }
    }
}
