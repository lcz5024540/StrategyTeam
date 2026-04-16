package com.metaverse.coin.strategy.core.arbitrage.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Getter
@Component
public class SingleExchangeSpotFuturesProperties {

    private String symbol = "BTCUSDT";
    private BigDecimal tradeUsdtAmount = new BigDecimal("100");
    private Integer leverage = 3;
    private BigDecimal openThreshold = new BigDecimal("0.0005");
    private BigDecimal closeThreshold = new BigDecimal("0.0001");
    private BigDecimal minBasisRate = BigDecimal.ZERO;
    private BigDecimal maxBasisRate = new BigDecimal("0.0030");
    private BigDecimal maxBasisDeteriorationRate = new BigDecimal("0.0015");
    private BigDecimal estimatedRoundTripFeeRate = new BigDecimal("0.0010");
    private BigDecimal estimatedSlippageRate = new BigDecimal("0.0005");
    private Integer minFundingPeriods = 4;
    private BigDecimal minNetYieldRate = new BigDecimal("0.0002");
    private Long settlementProtectionWindowMinutes = 5L;
    private BigDecimal maxPositionValueUsdt = new BigDecimal("1000");
    private BigDecimal minMarginRatio = new BigDecimal("2.0");
    private BigDecimal requiredMarginBufferRate = new BigDecimal("1.10");
    private BigDecimal emergencyCloseMarginRatio = new BigDecimal("1.80");
    private Boolean rebalanceEnabled = true;
    private BigDecimal rebalanceThresholdMarginRatio = new BigDecimal("1.5");
    private BigDecimal rebalanceTargetMarginRatio = new BigDecimal("3.0");
    private String rebalanceAsset = "USDT";

    public SingleExchangeSpotFuturesProperties() {
    }

    @Autowired
    public SingleExchangeSpotFuturesProperties(Environment environment) {
        symbol = environment.getProperty("arbitrage.spot-futures.symbol", symbol);
        tradeUsdtAmount = getBigDecimal(environment, "arbitrage.spot-futures.trade-usdt-amount", tradeUsdtAmount);
        leverage = getInteger(environment, "arbitrage.spot-futures.leverage", leverage);
        openThreshold = getBigDecimal(environment, "arbitrage.spot-futures.open-threshold", openThreshold);
        closeThreshold = getBigDecimal(environment, "arbitrage.spot-futures.close-threshold", closeThreshold);
        minBasisRate = getBigDecimal(environment, "arbitrage.spot-futures.min-basis-rate", minBasisRate);
        maxBasisRate = getBigDecimal(environment, "arbitrage.spot-futures.max-basis-rate", maxBasisRate);
        maxBasisDeteriorationRate = getBigDecimal(environment, "arbitrage.spot-futures.max-basis-deterioration-rate", maxBasisDeteriorationRate);
        estimatedRoundTripFeeRate = getBigDecimal(environment, "arbitrage.spot-futures.estimated-round-trip-fee-rate", estimatedRoundTripFeeRate);
        estimatedSlippageRate = getBigDecimal(environment, "arbitrage.spot-futures.estimated-slippage-rate", estimatedSlippageRate);
        minFundingPeriods = getInteger(environment, "arbitrage.spot-futures.min-funding-periods", minFundingPeriods);
        minNetYieldRate = getBigDecimal(environment, "arbitrage.spot-futures.min-net-yield-rate", minNetYieldRate);
        settlementProtectionWindowMinutes = getLong(environment, "arbitrage.spot-futures.settlement-protection-window-minutes", settlementProtectionWindowMinutes);
        maxPositionValueUsdt = getBigDecimal(environment, "arbitrage.spot-futures.max-position-value-usdt", maxPositionValueUsdt);
        minMarginRatio = getBigDecimal(environment, "arbitrage.spot-futures.min-margin-ratio", minMarginRatio);
        requiredMarginBufferRate = getBigDecimal(environment, "arbitrage.spot-futures.required-margin-buffer-rate", requiredMarginBufferRate);
        emergencyCloseMarginRatio = getBigDecimal(environment, "arbitrage.spot-futures.emergency-close-margin-ratio", emergencyCloseMarginRatio);
        rebalanceEnabled = getBoolean(environment, "arbitrage.spot-futures.rebalance-enabled", rebalanceEnabled);
        rebalanceThresholdMarginRatio = getBigDecimal(environment, "arbitrage.spot-futures.rebalance-threshold-margin-ratio", rebalanceThresholdMarginRatio);
        rebalanceTargetMarginRatio = getBigDecimal(environment, "arbitrage.spot-futures.rebalance-target-margin-ratio", rebalanceTargetMarginRatio);
        rebalanceAsset = environment.getProperty("arbitrage.spot-futures.rebalance-asset", rebalanceAsset);
    }

    private BigDecimal getBigDecimal(Environment environment, String key, BigDecimal defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : new BigDecimal(value);
    }

    private Integer getInteger(Environment environment, String key, Integer defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : Integer.valueOf(value);
    }

    private Long getLong(Environment environment, String key, Long defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : Long.valueOf(value);
    }

    private Boolean getBoolean(Environment environment, String key, Boolean defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : Boolean.valueOf(value);
    }
}
