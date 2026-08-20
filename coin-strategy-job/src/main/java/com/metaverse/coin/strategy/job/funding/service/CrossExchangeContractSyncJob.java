package com.metaverse.coin.strategy.job.funding.service;

import com.metaverse.coin.strategy.core.arbitrage.multi.PerpetualContractCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "arbitrage.multi-exchange", name = "contract-sync-enabled",
        havingValue = "true")
public class CrossExchangeContractSyncJob {
    private final PerpetualContractCatalog catalog;

    public CrossExchangeContractSyncJob(PerpetualContractCatalog catalog) {
        this.catalog = catalog;
    }

    @Scheduled(fixedDelayString = "${arbitrage.multi-exchange.contract-sync-delay-ms:86400000}")
    public void sync() {
        catalog.refresh(Instant.now());
    }
}
