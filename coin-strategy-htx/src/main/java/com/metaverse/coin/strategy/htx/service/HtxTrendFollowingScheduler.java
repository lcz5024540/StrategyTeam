package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.config.HtxTrendProperties;
import com.metaverse.coin.strategy.htx.persistence.HtxPersistenceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Periodic runner for HTX contract trend following. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(HtxTrendFollowingService.class)
@ConditionalOnProperty(prefix = "htx.trend", name = "enabled", havingValue = "true")
public class HtxTrendFollowingScheduler {

    private final HtxTrendFollowingService service;
    private final HtxTrendProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            fixedDelayString = "${htx.trend.poll-interval-ms:60000}",
            initialDelayString = "${htx.trend.initial-delay-ms:30000}")
    public void scheduled() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[HTX-TREND] previous scheduled run is still active; skip overlapping tick");
            return;
        }
        try {
            HtxTrendFollowingService.RunResult result = scheduledAt();
            log.info("[HTX-TREND] result={}", result);
        } catch (HtxPersistenceException ex) {
            log.error("[HTX-TREND] persistence failure; no new order will be attempted", ex);
        } catch (RuntimeException ex) {
            log.error("[HTX-TREND] scheduled run failed", ex);
        } finally {
            running.set(false);
        }
    }

    HtxTrendFollowingService.RunResult scheduledAt() {
        BooleanSupplier entryWindowAtExecution = () -> entryWindowOpen(Instant.now());
        return service.runOnce(entryWindowAtExecution);
    }

    private boolean entryWindowOpen(Instant now) {
        if (!properties.isAlignEntryToCandleClose()) {
            return true;
        }
        long ageSeconds = ageSinceLastCandleClose(now);
        long start = Math.max(0L, properties.getEntryWindowStartSecondsAfterClose());
        long duration = Math.max(1L, properties.getEntryWindowDurationSeconds());
        return ageSeconds >= start && ageSeconds <= start + duration;
    }

    private long ageSinceLastCandleClose(Instant now) {
        long periodSeconds = periodSeconds(properties.getPeriod());
        long epochSeconds = now.getEpochSecond();
        long lastClose = epochSeconds - Math.floorMod(epochSeconds, periodSeconds);
        return epochSeconds - lastClose;
    }

    private static long periodSeconds(String period) {
        String value = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("min")) {
            return Long.parseLong(value.substring(0, value.length() - 3)) * 60L;
        }
        if (value.endsWith("hour")) {
            return Long.parseLong(value.substring(0, value.length() - 4)) * 3_600L;
        }
        if (value.endsWith("day")) {
            return Long.parseLong(value.substring(0, value.length() - 3)) * 86_400L;
        }
        throw new IllegalArgumentException("unsupported HTX trend period: " + period);
    }
}
