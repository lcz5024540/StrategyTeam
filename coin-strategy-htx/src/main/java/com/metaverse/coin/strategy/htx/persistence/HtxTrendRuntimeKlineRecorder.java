package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineCandleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendRuntimeKlineSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendRuntimeKlineCandleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendRuntimeKlineSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Captures the exact public K-line payload made available to a runtime calculation.
 *
 * <p>Windows are immutable references. Candles are content-addressed independently, so repeated polling does not
 * duplicate unchanged bars and a corrected exchange bar remains recoverable as a new version.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HtxTrendRuntimeKlineRecorder {

    public static final String SOURCE_KIND = "HTX_PUBLIC_RUNTIME";
    private static final int INSERT_BATCH_SIZE = 32;
    private static final int DEADLOCK_RETRY_LIMIT = 3;
    private final HtxTrendRuntimeKlineCandleMapper candleMapper;
    private final HtxTrendRuntimeKlineSnapshotMapper snapshotMapper;
    /** One writer preserves append-only evidence while candidate scanning remains concurrent. */
    private final ReentrantLock writeLock = new ReentrantLock();

    /** Fail-open: evidence persistence must never turn a valid market signal into a duplicate trade attempt. */
    public void capture(CaptureRequest request) {
        if (!valid(request)) {
            return;
        }
        try {
            List<HtxSwapKline> candles = request.candles().stream()
                    .filter(candle -> candle != null && candle.getId() != null)
                    .sorted(Comparator.comparing(HtxSwapKline::getId))
                    .toList();
            if (candles.isEmpty()) {
                return;
            }
            String windowHash = windowHash(candles);
            long targetBarTime = request.targetBarTime() == null || request.targetBarTime() <= 0L
                    ? candles.get(candles.size() - 1).getId() : request.targetBarTime();
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
            snapshot.setExpectedWindowSize(Math.max(1, request.expectedWindowSize()));
            snapshot.setActualWindowSize(candles.size());
            snapshot.setCompleteWindow(candles.size() >= Math.max(1, request.expectedWindowSize()));
            snapshot.setWindowSha256(windowHash);
            snapshot.setSourceKind(SOURCE_KIND);
            snapshot.setSourceReference("market/history/kline");
            snapshot.setDataIntegrity(candles.size() >= Math.max(1, request.expectedWindowSize()) ? "COMPLETE" : "PARTIAL");
            snapshot.setCapturedAt(capturedAt);
            snapshotMapper.insert(snapshot);
        } catch (RuntimeException exception) {
            log.warn("runtime K-line evidence capture failed stage={} contract={} period={}", request.captureStage(),
                    request.contractCode(), request.period(), exception);
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
                log.warn("runtime K-line evidence deadlock; retry={}/{} batchSize={}", attempt,
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
