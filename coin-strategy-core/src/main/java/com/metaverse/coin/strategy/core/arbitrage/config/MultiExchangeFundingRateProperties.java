package com.metaverse.coin.strategy.core.arbitrage.config;

import com.metaverse.coin.strategy.core.arbitrage.multi.Exchange;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.Map;
import java.util.EnumMap;
import com.metaverse.coin.strategy.core.arbitrage.multi.ExchangeCost;
import java.util.stream.Collectors;
import java.time.Duration;

/** Configuration for cross-exchange futures funding arbitrage. Disabled by default. */
@Component
public class MultiExchangeFundingRateProperties {
    private final Set<Exchange> exchanges;
    private final BigDecimal minNetYieldRate;
    private final BigDecimal roundTripFeeRate;
    private final BigDecimal slippageRate;
    private final boolean allowMixedFundingSigns;
    private final Map<Exchange, ExchangeCost> costs;
    private final BigDecimal targetNotional;
    private final BigDecimal minimumNotional;
    private final BigDecimal minimumOrderBookNotional;
    private final BigDecimal minimumOrderBookCoverageRatio;
    private final BigDecimal maximumLegVwapImpact;
    private final int captureOrderBookDepth;
    private final BigDecimal paperCapitalPerExchange;
    private final BigDecimal maximumPaperLeverage;
    private final BigDecimal maintenanceMarginRate;
    private final BigDecimal minimumLiquidationDistance;
    private final BigDecimal unknownSymbolLiquidationDistance;
    private final BigDecimal maximumLiquidationDistance;
    private final BigDecimal volatilityMultiplier;
    private final BigDecimal jumpMultiplier;
    private final Duration minimumRiskHistory;
    private final BigDecimal basisRiskBuffer;
    private final BigDecimal maxMarkPriceDeviation;
    private final BigDecimal maxExecutableBasis;
    private final BigDecimal minimumCostCoverageRatio;
    private final BigDecimal maxSymbolNotional;
    private final BigDecimal maxExchangeNotional;
    private final boolean autoPaperOpenEnabled;
    private final BigDecimal autoPaperOpenNotional;
    private final int autoPaperOpenMaxPerCycle;
    private final BigDecimal exitNetYield;
    private final Duration maximumQuoteAge;
    private final Duration maximumSettlementSkew;
    private final Duration stabilityWindow;
    private final Duration maximumNakedDuration;
    private final Duration maximumHoldingDuration;

    public MultiExchangeFundingRateProperties() {
        this.exchanges = EnumSet.of(Exchange.BINANCE, Exchange.OKX, Exchange.HTX, Exchange.BITGET,
                Exchange.HYPERLIQUID);
        this.minNetYieldRate = new BigDecimal("0.0002");
        this.roundTripFeeRate = new BigDecimal("0.0010");
        this.slippageRate = new BigDecimal("0.0005");
        this.allowMixedFundingSigns = false;
        this.costs = defaultCosts();
        this.targetNotional = new BigDecimal("100");
        this.minimumNotional = new BigDecimal("20");
        this.minimumOrderBookNotional = new BigDecimal("300");
        this.minimumOrderBookCoverageRatio = new BigDecimal("3");
        this.maximumLegVwapImpact = new BigDecimal("0.0005");
        this.captureOrderBookDepth = 100;
        this.paperCapitalPerExchange = new BigDecimal("2000");
        this.maximumPaperLeverage = new BigDecimal("3");
        this.maintenanceMarginRate = new BigDecimal("0.005");
        this.minimumLiquidationDistance = new BigDecimal("0.15");
        this.unknownSymbolLiquidationDistance = new BigDecimal("0.30");
        this.maximumLiquidationDistance = new BigDecimal("0.40");
        this.volatilityMultiplier = new BigDecimal("4");
        this.jumpMultiplier = new BigDecimal("3");
        this.minimumRiskHistory = Duration.ofHours(1);
        this.basisRiskBuffer = new BigDecimal("0.0002");
        this.maxMarkPriceDeviation = new BigDecimal("0.01");
        this.maxExecutableBasis = new BigDecimal("0.003");
        this.minimumCostCoverageRatio = new BigDecimal("1.25");
        this.maxSymbolNotional = paperCapitalPerExchange.multiply(maximumPaperLeverage);
        this.maxExchangeNotional = paperCapitalPerExchange.multiply(maximumPaperLeverage);
        this.autoPaperOpenEnabled = false;
        this.autoPaperOpenNotional = BigDecimal.ZERO;
        this.autoPaperOpenMaxPerCycle = 1;
        this.exitNetYield = new BigDecimal("0.00001");
        this.maximumQuoteAge = Duration.ofSeconds(30);
        this.maximumSettlementSkew = Duration.ofMinutes(5);
        this.stabilityWindow = Duration.ofMinutes(3);
        this.maximumNakedDuration = Duration.ofSeconds(10);
        this.maximumHoldingDuration = Duration.ofDays(7);
    }

    @Autowired
    public MultiExchangeFundingRateProperties(Environment env) {
        String configured = env.getProperty("arbitrage.multi-exchange.exchanges", "BINANCE,OKX,HTX,BITGET,HYPERLIQUID");
        this.exchanges = configured.isBlank() ? Collections.emptySet() : Arrays.stream(configured.split(","))
                .map(Exchange::parse).collect(Collectors.toCollection(() -> EnumSet.noneOf(Exchange.class)));
        this.minNetYieldRate = decimal(env, "arbitrage.multi-exchange.min-net-yield-rate", "0.0002");
        this.roundTripFeeRate = decimal(env, "arbitrage.multi-exchange.round-trip-fee-rate", "0.0010");
        this.slippageRate = decimal(env, "arbitrage.multi-exchange.slippage-rate", "0.0005");
        this.allowMixedFundingSigns = Boolean.parseBoolean(env.getProperty("arbitrage.multi-exchange.allow-mixed-funding-signs", "false"));
        this.costs = configuredCosts(env);
        this.targetNotional = decimal(env, "arbitrage.multi-exchange.target-notional", "100");
        this.minimumNotional = decimal(env, "arbitrage.multi-exchange.minimum-notional", "20");
        this.minimumOrderBookNotional = decimal(env,
                "arbitrage.multi-exchange.minimum-order-book-notional", "300");
        this.minimumOrderBookCoverageRatio = decimal(env,
                "arbitrage.multi-exchange.minimum-order-book-coverage-ratio", "3");
        this.maximumLegVwapImpact = decimal(env,
                "arbitrage.multi-exchange.maximum-leg-vwap-impact", "0.0005");
        this.captureOrderBookDepth = positiveInt(env,
                "arbitrage.multi-exchange.capture-order-book-depth", 100);
        this.paperCapitalPerExchange = decimal(env, "arbitrage.multi-exchange.paper-capital-per-exchange", "2000");
        this.maximumPaperLeverage = decimal(env, "arbitrage.multi-exchange.maximum-paper-leverage", "3");
        this.maintenanceMarginRate = decimal(env, "arbitrage.multi-exchange.maintenance-margin-rate", "0.005");
        this.minimumLiquidationDistance = decimal(env, "arbitrage.multi-exchange.minimum-liquidation-distance", "0.15");
        this.unknownSymbolLiquidationDistance = decimal(env, "arbitrage.multi-exchange.unknown-symbol-liquidation-distance", "0.30");
        this.maximumLiquidationDistance = decimal(env, "arbitrage.multi-exchange.maximum-liquidation-distance", "0.40");
        this.volatilityMultiplier = decimal(env, "arbitrage.multi-exchange.volatility-multiplier", "4");
        this.jumpMultiplier = decimal(env, "arbitrage.multi-exchange.jump-multiplier", "3");
        this.minimumRiskHistory = Duration.ofMillis(longValue(env, "arbitrage.multi-exchange.minimum-risk-history-ms", 3600000L));
        this.basisRiskBuffer = decimal(env, "arbitrage.multi-exchange.basis-risk-buffer", "0.0002");
        this.maxMarkPriceDeviation = decimal(env, "arbitrage.multi-exchange.max-mark-price-deviation", "0.01");
        this.maxExecutableBasis = decimal(env, "arbitrage.multi-exchange.max-executable-basis", "0.003");
        this.minimumCostCoverageRatio = decimal(env, "arbitrage.multi-exchange.minimum-cost-coverage-ratio", "1.25");
        BigDecimal leverageBound = paperCapitalPerExchange.multiply(maximumPaperLeverage);
        this.maxSymbolNotional = decimal(env, "arbitrage.multi-exchange.max-symbol-notional",
                leverageBound.toPlainString());
        this.maxExchangeNotional = decimal(env, "arbitrage.multi-exchange.max-exchange-notional",
                leverageBound.toPlainString());
        this.autoPaperOpenEnabled = Boolean.parseBoolean(env.getProperty(
                "arbitrage.multi-exchange.auto-paper-open-enabled", "false"));
        this.autoPaperOpenNotional = decimal(env,
                "arbitrage.multi-exchange.auto-paper-open-notional", "0");
        this.autoPaperOpenMaxPerCycle = positiveInt(env,
                "arbitrage.multi-exchange.auto-paper-open-max-per-cycle", 1);
        this.exitNetYield = decimal(env, "arbitrage.multi-exchange.exit-net-yield", "0.00001");
        this.maximumQuoteAge = Duration.ofMillis(longValue(env, "arbitrage.multi-exchange.maximum-quote-age-ms", 30000L));
        this.maximumSettlementSkew = Duration.ofMillis(longValue(env, "arbitrage.multi-exchange.maximum-settlement-skew-ms", 300000L));
        this.stabilityWindow = Duration.ofMillis(longValue(env, "arbitrage.multi-exchange.stability-window-ms", 180000L));
        this.maximumNakedDuration = Duration.ofMillis(longValue(env, "arbitrage.multi-exchange.maximum-naked-duration-ms", 10000L));
        this.maximumHoldingDuration = Duration.ofMillis(longValue(env, "arbitrage.multi-exchange.maximum-holding-duration-ms", 604800000L));
    }
    private static BigDecimal decimal(Environment e, String key, String fallback) {
        return new BigDecimal(e.getProperty(key, fallback));
    }
    public Set<Exchange> getExchanges() { return Collections.unmodifiableSet(exchanges); }
    public BigDecimal getMinNetYieldRate() { return minNetYieldRate; }
    public BigDecimal getRoundTripFeeRate() { return roundTripFeeRate; }
    public BigDecimal getSlippageRate() { return slippageRate; }
    public boolean isAllowMixedFundingSigns() { return allowMixedFundingSigns; }
    public Map<Exchange, ExchangeCost> getCosts() { return Collections.unmodifiableMap(costs); }
    public BigDecimal getTargetNotional() { return targetNotional; }
    public BigDecimal getMinimumNotional() { return minimumNotional; }
    public BigDecimal getMinimumOrderBookNotional() { return minimumOrderBookNotional; }
    public BigDecimal getMinimumOrderBookCoverageRatio() { return minimumOrderBookCoverageRatio; }
    public BigDecimal getMaximumLegVwapImpact() { return maximumLegVwapImpact; }
    public int getCaptureOrderBookDepth() { return captureOrderBookDepth; }
    public BigDecimal getPaperCapitalPerExchange() { return paperCapitalPerExchange; }
    public BigDecimal getMaximumPaperLeverage() { return maximumPaperLeverage; }
    public BigDecimal getMaintenanceMarginRate() { return maintenanceMarginRate; }
    public BigDecimal getMinimumLiquidationDistance() { return minimumLiquidationDistance; }
    public BigDecimal getUnknownSymbolLiquidationDistance() { return unknownSymbolLiquidationDistance; }
    public BigDecimal getMaximumLiquidationDistance() { return maximumLiquidationDistance; }
    public BigDecimal getVolatilityMultiplier() { return volatilityMultiplier; }
    public BigDecimal getJumpMultiplier() { return jumpMultiplier; }
    public Duration getMinimumRiskHistory() { return minimumRiskHistory; }
    public BigDecimal getBasisRiskBuffer() { return basisRiskBuffer; }
    public BigDecimal getMaxMarkPriceDeviation() { return maxMarkPriceDeviation; }
    public BigDecimal getMaxExecutableBasis() { return maxExecutableBasis; }
    public BigDecimal getMinimumCostCoverageRatio() { return minimumCostCoverageRatio; }
    public BigDecimal getMaxSymbolNotional() { return maxSymbolNotional; }
    public BigDecimal getMaxExchangeNotional() { return maxExchangeNotional; }
    public boolean isAutoPaperOpenEnabled() { return autoPaperOpenEnabled; }
    public BigDecimal getAutoPaperOpenNotional() { return autoPaperOpenNotional; }
    public int getAutoPaperOpenMaxPerCycle() { return autoPaperOpenMaxPerCycle; }
    public BigDecimal getExitNetYield() { return exitNetYield; }
    public Duration getMaximumQuoteAge() { return maximumQuoteAge; }
    public Duration getMaximumSettlementSkew() { return maximumSettlementSkew; }
    public Duration getStabilityWindow() { return stabilityWindow; }
    public Duration getMaximumNakedDuration() { return maximumNakedDuration; }
    public Duration getMaximumHoldingDuration() { return maximumHoldingDuration; }
    private static long longValue(Environment environment, String key, long fallback) {
        return Long.parseLong(environment.getProperty(key, String.valueOf(fallback)));
    }
    private static int positiveInt(Environment environment, String key, int fallback) {
        int value = Integer.parseInt(environment.getProperty(key, String.valueOf(fallback)));
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }
    private static Map<Exchange, ExchangeCost> defaultCosts() {
        Map<Exchange, ExchangeCost> result = new EnumMap<>(Exchange.class);
        ExchangeCost standard = new ExchangeCost(new BigDecimal("0.0002"), new BigDecimal("0.0005"), new BigDecimal("0.0005"));
        for (Exchange exchange : Exchange.values()) result.put(exchange, standard);
        return result;
    }
    private static Map<Exchange, ExchangeCost> configuredCosts(Environment env) {
        Map<Exchange, ExchangeCost> result = new EnumMap<>(Exchange.class);
        for (Exchange exchange : Exchange.values()) {
            String prefix = "arbitrage.multi-exchange.costs." + exchange.name().toLowerCase() + ".";
            result.put(exchange, new ExchangeCost(decimal(env, prefix + "maker-fee-rate", "0.0002"),
                    decimal(env, prefix + "taker-fee-rate", "0.0005"),
                    decimal(env, prefix + "slippage-rate", "0.0005"),
                    decimal(env, prefix + "capital-transfer-cost-rate", "0")));
        }
        return result;
    }
}
