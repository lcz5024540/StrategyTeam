package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeArbitragePersistenceService;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunity;
import com.metaverse.coin.strategy.core.arbitrage.multi.CrossExchangeOpportunityScanService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "scan-enabled", havingValue = "true")
public class CrossExchangeFundingScanJob {
    private final CrossExchangeOpportunityScanService scanner;
    private final CrossExchangeArbitragePersistenceService persistence;
    private final CrossExchangeMetrics metrics;
    private final Clock clock;

    public CrossExchangeFundingScanJob(CrossExchangeOpportunityScanService scanner,
            CrossExchangeArbitragePersistenceService persistence, CrossExchangeMetrics metrics) {
        this(scanner, persistence, metrics, Clock.systemUTC());
    }

    CrossExchangeFundingScanJob(CrossExchangeOpportunityScanService scanner,
            CrossExchangeArbitragePersistenceService persistence, CrossExchangeMetrics metrics,
            Clock clock) {
        this.scanner = scanner;
        this.persistence = persistence;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.scan-delay-ms:30000}")
    public void scan() {
        int found = 0;
        for (String symbol : scanner.commonSymbols()) {
            Instant now = Instant.now(clock);
            found += scanner.scan(symbol, now).size();
            for (CrossExchangeOpportunity opportunity : scanner.lastEvaluated()) {
                persistence.saveOpportunity(opportunity);
            }
        }
        metrics.scan(found);
    }
}
