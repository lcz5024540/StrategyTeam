package com.metaverse.coin.strategy.htx.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic runner for the default-off trend ensemble shadow observer. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(HtxTrendEnsembleShadowService.class)
@ConditionalOnProperty(prefix = "htx.trend.ensemble-shadow", name = "enabled", havingValue = "true")
public class HtxTrendEnsembleShadowScheduler {

    private final HtxTrendEnsembleShadowService service;

    @Scheduled(
            fixedDelayString = "${htx.trend.ensemble-shadow.poll-interval-ms:60000}",
            initialDelayString = "${htx.trend.ensemble-shadow.initial-delay-ms:45000}")
    public void scheduled() {
        try {
            HtxTrendEnsembleShadowService.RunResult result = service.runOnce();
            log.info("[HTX-TREND-ENSEMBLE] result={}", result);
        } catch (RuntimeException ex) {
            log.error("[HTX-TREND-ENSEMBLE] scheduled shadow run failed", ex);
        }
    }
}
