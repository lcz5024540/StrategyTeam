package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic runner for HTX funding arbitrage. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(HtxFundingArbitrageService.class)
@ConditionalOnProperty(prefix = "htx.funding-arbitrage", name = "enabled", havingValue = "true")
public class HtxFundingArbitrageScheduler {

    private final HtxFundingArbitrageService service;

    @Scheduled(
            fixedDelayString = "${htx.funding-arbitrage.poll-interval-ms:30000}",
            initialDelayString = "${htx.funding-arbitrage.initial-delay-ms:20000}")
    public void scheduled() {
        try {
            HtxFundingArbitrageService.RunResult result = service.runOnce();
            log.info("[HTX-FUNDING-ARB] result={}", result);
        } catch (HtxPersistenceException ex) {
            log.error("[HTX-FUNDING-ARB] persistence failure; no new order will be attempted", ex);
        } catch (RuntimeException ex) {
            log.error("[HTX-FUNDING-ARB] scheduled run failed", ex);
        }
    }
}
