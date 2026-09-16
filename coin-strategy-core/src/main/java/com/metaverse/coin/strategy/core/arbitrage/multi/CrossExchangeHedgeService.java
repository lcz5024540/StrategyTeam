package com.metaverse.coin.strategy.core.arbitrage.multi;

import lombok.extern.slf4j.Slf4j;
import com.metaverse.coin.strategy.core.arbitrage.config.MultiExchangeFundingRateProperties;

import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Coordinates the two exchange legs and guarantees compensation on a partial open. */
@Slf4j
public class CrossExchangeHedgeService {
    private final Map<Exchange, ExchangeFundingGateway> gateways;
    private final MultiExchangeFundingRateProperties properties;

    public CrossExchangeHedgeService(Map<Exchange, ExchangeFundingGateway> gateways) {
        this(gateways, new MultiExchangeFundingRateProperties());
    }

    public CrossExchangeHedgeService(Map<Exchange, ExchangeFundingGateway> gateways,
                                     MultiExchangeFundingRateProperties properties) {
        this.gateways = gateways;
        this.properties = properties;
    }

    /**
     * First stage of the trading chain: compare all configured exchanges for the
     * same symbols and return the best net funding opportunity. No order is sent.
     */
    public java.util.Optional<FundingRateSpread> compareFundingRates(List<String> symbols) {
        List<FundingRateQuote> quotes = new ArrayList<>();
        for (Exchange exchange : properties.getExchanges()) {
            ExchangeFundingGateway gateway = gateways.get(exchange);
            if (gateway == null) {
                log.warn("Funding gateway is not configured, skip exchange={}", exchange);
                continue;
            }
            try {
                List<FundingRateQuote> received = gateway.fundingRates(symbols);
                if (received != null) quotes.addAll(received);
            } catch (Exception e) {
                log.warn("Funding rate query failed, exchange={}", exchange, e);
            }
        }
        return FundingRateSpreadCalculator.best(quotes, properties.getCosts(),
                properties.getMinNetYieldRate(), properties.isAllowMixedFundingSigns());
    }

    /** Compare first, then execute only when the net funding yield passes all gates. */
    public CrossExchangeHedgeExecution compareAndOpen(String symbol, BigDecimal quantity) {
        FundingRateSpread spread = compareFundingRates(List.of(symbol)).orElse(null);
        if (spread == null) return null;
        return open(spread, quantity);
    }

    public CrossExchangeHedgeExecution open(FundingRateSpread spread, BigDecimal quantity) {
        CrossExchangeHedgePlan plan = new CrossExchangeHedgePlan(spread,
                new HedgeLeg(spread.longLeg().exchange(), spread.longLeg().symbol(), HedgeSide.LONG, quantity),
                new HedgeLeg(spread.shortLeg().exchange(), spread.shortLeg().symbol(), HedgeSide.SHORT, quantity));
        String id = UUID.randomUUID().toString();
        boolean longOpened = false;
        try {
            gateway(plan.longLeg()).openLeg(plan.longLeg(), id + "-L");
            longOpened = true;
            gateway(plan.shortLeg()).openLeg(plan.shortLeg(), id + "-S");
            return new CrossExchangeHedgeExecution(id, plan, CrossExchangeHedgeExecution.Status.OPENED, null);
        } catch (Exception failure) {
            if (longOpened) {
                try { gateway(plan.longLeg()).closeLeg(plan.longLeg(), id + "-ROLLBACK-L"); }
                catch (Exception rollbackFailure) { log.error("Cross-exchange hedge rollback failed, executionId={}", id, rollbackFailure); }
            }
            return new CrossExchangeHedgeExecution(id, plan,
                    longOpened ? CrossExchangeHedgeExecution.Status.ROLLED_BACK : CrossExchangeHedgeExecution.Status.FAILED,
                    failure.getMessage());
        }
    }

    public CrossExchangeHedgeExecution close(CrossExchangeHedgeExecution opened) {
        String id = opened.executionId();
        try {
            gateway(opened.plan().longLeg()).closeLeg(opened.plan().longLeg(), id + "-CLOSE-L");
            gateway(opened.plan().shortLeg()).closeLeg(opened.plan().shortLeg(), id + "-CLOSE-S");
            return new CrossExchangeHedgeExecution(id, opened.plan(), CrossExchangeHedgeExecution.Status.CLOSED, null);
        } catch (Exception failure) {
            return new CrossExchangeHedgeExecution(id, opened.plan(), CrossExchangeHedgeExecution.Status.FAILED, failure.getMessage());
        }
    }

    private ExchangeFundingGateway gateway(HedgeLeg leg) {
        ExchangeFundingGateway gateway = gateways.get(leg.exchange());
        if (gateway == null) throw new IllegalStateException("No gateway configured for " + leg.exchange());
        return gateway;
    }
}
