package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineCandleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendRuntimeKlineCandleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendRuntimeKlineSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captures the exact public K-line payload made available to a runtime calculation.
 *
 * <p>Windows are immutable references. Candles are content-addressed independently, so repeated polling does not
 * duplicate unchanged bars and a corrected exchange bar remains recoverable as a new version.</p>
 */
@Slf4j
@Component
public class HtxTrendRuntimeKlineRecorder {

    public static final String SOURCE_KIND = "HTX_PUBLIC_RUNTIME";
    private static final int INSERT_BATCH_SIZE = 32;
    private static final int DEADLOCK_RETRY_LIMIT = 3;
    /** Evidence must yield to execution. This queue absorbs one full multi-period scan without blocking it. */
    private static final int ASYNC_QUEUE_CAPACITY = 2_048;
    private final HtxTrendRuntimeKlineCandleMapper candleMapper;
    private final HtxTrendRuntimeKlineSnapshotMapper snapshotMapper;
    private final ThreadPoolExecutor writer;
    /** One writer preserves append-only evidence while candidate scanning remains concurrent. */
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong captureAttempts = new AtomicLong();
    private final AtomicLong captureCompleted = new AtomicLong();
    private final AtomicLong completeWindows = new AtomicLong();
    private final AtomicLong partialWindows = new AtomicLong();
    private final AtomicLong deadlockRetries = new AtomicLong();
    private final AtomicLong finalFailures = new AtomicLong();
    private final AtomicLong captureLatencyMs = new AtomicLong();
    private final AtomicLong rejectedCaptures = new AtomicLong();

    public HtxTrendRuntimeKlineRecorder(HtxTrendRuntimeKlineCandleMapper candleMapper,
                                        HtxTrendRuntimeKlineSnapshotMapper snapshotMapper) {
        this.candleMapper = candleMapper;
        this.snapshotMapper = snapshotMapper;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "htx-runtime-kline-evidence");
            thread.setDaemon(true);
            return thread;
        };
        this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(ASYNC_QUEUE_CAPACITY), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Fail-open and non-blocking: the trade scanner only enqueues evidence.  Database latency, a deadlock or a full
     * evidence queue can never consume an entry window or change a trade decision.
     */
    public void capture(CaptureRequest request) {
        if (!valid(request)) {
            return;
        }
        captureAttempts.incrementAndGet();
        try {
            writer.execute(() -> persist(request));
        } catch (RuntimeException exception) {
            rejectedCaptures.incrementAndGet();
            log.warn("[HTX-TREND-KLINE] capture dropped stage={} contract={} period={} queueDepth={} reason={}",
                    request.captureStage(), request.contractCode(), request.period(), writer.getQueue().size(), rootMessage(exception));
        }
    }

    private void persist(CaptureRequest request) {
        long startedAt = System.nanoTime();
        try {
            List<HtxSwapKline> candles = request.candles().stream()
                    .filter(candle -> candle != null && candle.getId() != null)
                    .sorted(Comparator.comparing(HtxSwapKline::getId))
                    .toList();
            if (candles.isEmpty()) {
                return;
            }
            // Callers pass only completed bars.  Clamp the decision identity to the captured window so a
            // lower-timeframe target cannot falsely label a still-forming higher-timeframe bar as evidence.
            long lastCapturedBarTime = candles.get(candles.size() - 1).getId();
            long targetBarTime = request.targetBarTime() == null || request.targetBarTime() <= 0L
                    ? lastCapturedBarTime : Math.min(request.targetBarTime(), lastCapturedBarTime);
            String windowHash = windowHash(candles);
            String snapshotKey = String.join(":", "runtime-kline-v1", request.captureStage(), request.contractCode(),
                    request.period(), String.valueOf(targetBarTime), windowHash, safeKey(request.runId()),
                    request.positionId() == null ? "-" : String.valueOf(request.positionId()),
                    request.factorSnapshotId() == null ? "-" : String.valueOf(request.factorSnapshotId()));
            if (snapshotMapper.findBySnapshotKey(snapshotKey) != null) {
                return;
            }
            LocalDateTime capturedAt = LocalDateTime.now();
            List<HtxTrendRuntimeKlineCandleEntity> entities = new ArrayList<>(candles.size());
            for (HtxSwapKline candle : candles) {
                entities.add(toCandle(request, candle, capturedAt));
            }
            persistCandles(entities);
            HtxTrendRuntimeKlineSnapshotEntity snapshot = new HtxTrendRuntimeKlineSnapshotEntity();
            snapshot.setSnapshotKey(snapshotKey);
            snapshot.setRunId(blankToNull(request.runId()));
            snapshot.setFactorSnapshotId(request.factorSnapshotId());
            snapshot.setPositionId(request.positionId());
            snapshot.setCaptureStage(request.captureStage());
            snapshot.setContractCode(request.contractCode());
            snapshot.setPeriod(request.period());
            snapshot.setTargetBarTime(targetBarTime);
            snapshot.setWindowStartBarTime(candles.get(0).getId());
            snapshot.setWindowEndBarTime(candles.get(candles.size() - 1).getId());
            int expectedWindowSize = Math.max(1, request.expectedWindowSize());
            boolean completeWindow = completeWindow(candles, request.period(), expectedWindowSize);
            snapshot.setExpectedWindowSize(expectedWindowSize);
            snapshot.setActualWindowSize(candles.size());
            snapshot.setCompleteWindow(completeWindow);
            snapshot.setWindowSha256(windowHash);
            snapshot.setSourceKind(SOURCE_KIND);
            snapshot.setSourceReference("market/history/kline");
            snapshot.setDataIntegrity(completeWindow ? "COMPLETE" : "PARTIAL");
            snapshot.setCapturedAt(capturedAt);
            snapshotMapper.insert(snapshot);
            captureCompleted.incrementAndGet();
            if (snapshot.getCompleteWindow()) completeWindows.incrementAndGet();
            else partialWindows.incrementAndGet();
        } catch (RuntimeException exception) {
            finalFailures.incrementAndGet();
            log.warn("[HTX-TREND-KLINE] capture failed stage={} contract={} period={} reason={}", request.captureStage(),
                    request.contractCode(), request.period(), rootMessage(exception));
        } finally {
            captureLatencyMs.addAndGet((System.nanoTime() - startedAt) / 1_000_000L);
        }
    }

    private void persistCandles(List<HtxTrendRuntimeKlineCandleEntity> entities) {
        writeLock.lock();
        try {
            for (int offset = 0; offset < entities.size(); offset += INSERT_BATCH_SIZE) {
                List<HtxTrendRuntimeKlineCandleEntity> batch = entities.subList(offset,
                        Math.min(entities.size(), offset + INSERT_BATCH_SIZE));
                insertWithRetry(batch);
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void insertWithRetry(List<HtxTrendRuntimeKlineCandleEntity> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= DEADLOCK_RETRY_LIMIT; attempt++) {
            try {
                candleMapper.insertIgnoreBatch(batch);
                return;
            } catch (RuntimeException exception) {
                last = exception;
                if (!deadlock(exception) || attempt == DEADLOCK_RETRY_LIMIT) break;
                deadlockRetries.incrementAndGet();
                log.warn("[HTX-TREND-KLINE] deadlock retry={}/{} batchSize={}", attempt,
                        DEADLOCK_RETRY_LIMIT, batch.size());
            }
        }
        throw last == null ? new IllegalStateException("runtime K-line evidence write failed") : last;
    }

    private static boolean deadlock(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("deadlock")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) current = current.getCause();
        return current == null || current.getMessage() == null ? "unknown" : current.getMessage();
    }

    /** Cumulative in-process quality signal; counters are diagnostic and never gate an order. */
    public QualitySnapshot qualitySnapshot() {
        return new QualitySnapshot(captureAttempts.get(), captureCompleted.get(), completeWindows.get(), partialWindows.get(),
                deadlockRetries.get(), finalFailures.get(), rejectedCaptures.get(), writer.getQueue().size(), captureLatencyMs.get());
    }

    public record QualitySnapshot(long attempts, long completed, long completeWindows, long partialWindows,
                                  long deadlockRetries, long finalFailures, long rejectedCaptures, long queueDepth,
                                  long totalLatencyMs) {
        public static QualitySnapshot unavailable() { return new QualitySnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0); }
        public String summary() {
            return "attempts=" + attempts + ",completed=" + completed + ",complete=" + completeWindows
                    + ",partial=" + partialWindows + ",deadlockRetries=" + deadlockRetries
                    + ",failures=" + finalFailures + ",dropped=" + rejectedCaptures + ",queueDepth=" + queueDepth
                    + ",latencyMs=" + totalLatencyMs;
        }
    }

    @PreDestroy
    void stopWriter() {
        writer.shutdown();
    }

    private static HtxTrendRuntimeKlineCandleEntity toCandle(CaptureRequest request, HtxSwapKline candle,
                                                              LocalDateTime capturedAt) {
        HtxTrendRuntimeKlineCandleEntity entity = new HtxTrendRuntimeKlineCandleEntity();
        entity.setCandleHash(candleHash(candle));
        entity.setContractCode(request.contractCode());
        entity.setPeriod(request.period());
        entity.setBarTime(candle.getId());
        entity.setOpenPrice(candle.getOpen());
        entity.setHighPrice(candle.getHigh());
        entity.setLowPrice(candle.getLow());
        entity.setClosePrice(candle.getClose());
        entity.setAmount(candle.getAmount());
        entity.setVolume(candle.getVol());
        entity.setTurnover(candle.getTradeTurnover());
        entity.setTradeCount(candle.getCount());
        entity.setSourceKind(SOURCE_KIND);
        entity.setCapturedAt(capturedAt);
        return entity;
    }

    private static boolean valid(CaptureRequest request) {
        return request != null && !blank(request.captureStage()) && !blank(request.contractCode())
                && !blank(request.period()) && request.candles() != null && !request.candles().isEmpty();
    }

    /** A full row count is insufficient evidence when HTX returned a window with a missing completed bar. */
    private static boolean completeWindow(List<HtxSwapKline> candles, String period, int expectedWindowSize) {
        if (candles.size() < expectedWindowSize) {
            return false;
        }
        long seconds;
        try {
            seconds = periodSeconds(period);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        for (int index = 1; index < candles.size(); index++) {
            Long previous = candles.get(index - 1).getId();
            Long current = candles.get(index).getId();
            if (previous == null || current == null || current - previous != seconds) {
                return false;
            }
        }
        return true;
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
        throw new IllegalArgumentException("unsupported runtime K-line period=" + period);
    }

    private static String windowHash(List<HtxSwapKline> candles) {
        StringBuilder canonical = new StringBuilder();
        for (HtxSwapKline candle : candles) {
            canonical.append(candleHash(candle)).append('\n');
        }
        return sha256(canonical.toString());
    }

    private static String candleHash(HtxSwapKline candle) {
        return sha256(canonical(candle.getId()) + '|' + canonical(candle.getOpen()) + '|' + canonical(candle.getHigh())
                + '|' + canonical(candle.getLow()) + '|' + canonical(candle.getClose()) + '|' + canonical(candle.getAmount())
                + '|' + canonical(candle.getVol()) + '|' + canonical(candle.getTradeTurnover()) + '|' + canonical(candle.getCount()));
    }

    private static String canonical(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String safeKey(String value) {
        return blank(value) ? "-" : sha256(value).substring(0, 16);
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CaptureRequest(String captureStage, String contractCode, String period, Long targetBarTime,
                                 int expectedWindowSize, String runId, Long factorSnapshotId, Long positionId,
                                 List<HtxSwapKline> candles) { }
}
