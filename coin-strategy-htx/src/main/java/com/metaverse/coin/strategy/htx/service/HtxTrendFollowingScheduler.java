package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic runner for HTX contract trend following. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(HtxTrendFollowingService.class)
@ConditionalOnProperty(prefix = "htx.trend", name = "enabled", havingValue = "true")
public class HtxTrendFollowingScheduler {

    private final HtxTrendFollowingService service;

    @Scheduled(
            fixedDelayString = "${htx.trend.poll-interval-ms:60000}",
            initialDelayString = "${htx.trend.initial-delay-ms:30000}")
    public void scheduled() {
        try {
            HtxTrendFollowingService.RunResult result = service.runOnce();
            log.info("[HTX-TREND] result={}", result);
        } catch (HtxPersistenceException ex) {
            log.error("[HTX-TREND] persistence failure; no new order will be attempted", ex);
        } catch (RuntimeException ex) {
            log.error("[HTX-TREND] scheduled run failed", ex);
        }
    }
}
