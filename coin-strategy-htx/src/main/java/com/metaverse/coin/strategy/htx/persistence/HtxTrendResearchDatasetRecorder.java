package com.metaverse.coin.strategy.htx.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchCandleEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchDatasetEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchCandleMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchDatasetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores an already-frozen research input set without reaching HTX or any execution component.
 *
 * <p>The caller owns collection and manifest generation. This recorder only validates the immutable
 * input, writes it in bounded batches, and records an explicit terminal state. A failed freeze is
 * intentionally not retried under the same dataset key: a corrected capture must receive a new key.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HtxTrendResearchDatasetRecorder {

    static final String FREEZING = "FREEZING";
    static final String FROZEN = "FROZEN";
    static final String FAILED = "FAILED";
    private static final int INSERT_BATCH_SIZE = 250;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> VALID_INTEGRITY = Set.of("COMPLETE", "PARTIAL", "INSUFFICIENT_EVIDENCE");
    private static final Map<String, Long> PERIOD_SECONDS = Map.of(
            "15min", 900L, "30min", 1800L, "60min", 3600L, "4hour", 14400L, "1day", 86400L);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HtxTrendResearchDatasetMapper datasetMapper;
    private final HtxTrendResearchCandleMapper candleMapper;

    public FreezeResult freeze(HtxTrendResearchDatasetFreezeRequest request) {
        validateRequest(request);
        HtxTrendResearchDatasetEntity existing = datasetMapper.findByDatasetKey(request.datasetKey());
        if (existing != null) {
            return resolveExisting(existing, request);
        }

        HtxTrendResearchDatasetEntity dataset = toEntity(request);
        datasetMapper.insert(dataset);
        int stored = 0;
        try {
            for (List<HtxTrendResearchCandleEntity> batch : batches(request.candles(), INSERT_BATCH_SIZE)) {
                stored += candleMapper.insertBatch(batch);
            }
            datasetMapper.finishFreeze(request.datasetKey(), FROZEN, freezeProvenance(request.provenanceJson(), FROZEN, stored, null));
            return new FreezeResult(request.datasetKey(), FROZEN, stored, false);
        } catch (RuntimeException exception) {
            String reason = rootMessage(exception);
            datasetMapper.finishFreeze(request.datasetKey(), FAILED, freezeProvenance(request.provenanceJson(), FAILED, stored, reason));
            log.error("research dataset freeze failed datasetKey={} storedCandles={} reason={}", request.datasetKey(), stored, reason);
            throw new ResearchDatasetFreezeException(request.datasetKey(), stored, exception);
        }
    }

    private FreezeResult resolveExisting(HtxTrendResearchDatasetEntity existing, HtxTrendResearchDatasetFreezeRequest request) {
        if (FROZEN.equals(existing.getStatus()) && request.inputChecksum().equals(existing.getInputChecksum())) {
            return new FreezeResult(request.datasetKey(), FROZEN, 0, true);
        }
        throw new IllegalStateException("research dataset key is already reserved by status=" + existing.getStatus()
                + "; corrected input must use a new dataset key");
    }

    private static HtxTrendResearchDatasetEntity toEntity(HtxTrendResearchDatasetFreezeRequest request) {
        HtxTrendResearchDatasetEntity entity = new HtxTrendResearchDatasetEntity();
        entity.setDatasetKey(request.datasetKey());
        entity.setSourceKind(request.sourceKind());
        entity.setSourceReference(request.sourceReference());
        entity.setCapturedAt(request.capturedAt());
        entity.setInputStartAt(request.inputStartAt());
        entity.setInputEndAt(request.inputEndAt());
        entity.setContractsJson(request.contractsJson());
        entity.setPeriodsJson(request.periodsJson());
        entity.setInputChecksum(request.inputChecksum());
        entity.setInputIntegrity(request.inputIntegrity());
        entity.setStatus(FREEZING);
        entity.setPurpose(request.purpose());
        entity.setProvenanceJson(request.provenanceJson());
        return entity;
    }

    private static void validateRequest(HtxTrendResearchDatasetFreezeRequest request) {
        if (request == null || blank(request.datasetKey()) || blank(request.sourceKind()) || blank(request.inputChecksum())
                || blank(request.contractsJson()) || blank(request.periodsJson()) || blank(request.inputIntegrity())
                || blank(request.purpose()) || request.capturedAt() == null || request.inputStartAt() == null
                || request.inputEndAt() == null || !request.inputStartAt().isBefore(request.inputEndAt())) {
            throw new IllegalArgumentException("research dataset request is incomplete");
        }
        if (!VALID_INTEGRITY.contains(request.inputIntegrity())) {
            throw new IllegalArgumentException("unsupported research dataset integrity=" + request.inputIntegrity());
        }
        if (request.candles() == null || request.candles().isEmpty()) {
            throw new IllegalArgumentException("research dataset must contain at least one frozen candle");
        }
        Set<String> identities = new HashSet<>();
        for (HtxTrendResearchCandleEntity candle : request.candles()) {
            validateCandle(request, candle, identities);
        }
        if ("COMPLETE".equals(request.inputIntegrity())) {
            validateCompleteCoverage(request);
        }
    }

    private static void validateCandle(HtxTrendResearchDatasetFreezeRequest request, HtxTrendResearchCandleEntity candle,
                                       Set<String> identities) {
        if (candle == null || blank(candle.getContractCode()) || blank(candle.getPeriod()) || candle.getBarTime() == null
                || priceInvalid(candle.getOpenPrice()) || priceInvalid(candle.getHighPrice()) || priceInvalid(candle.getLowPrice())
                || priceInvalid(candle.getClosePrice()) || blank(candle.getSourceKind())) {
            throw new IllegalArgumentException("research candle is incomplete");
        }
        if (!request.datasetKey().equals(candle.getDatasetKey())) {
            throw new IllegalArgumentException("research candle dataset key differs from its manifest");
        }
        if (candle.getHighPrice().compareTo(candle.getLowPrice()) < 0
                || candle.getHighPrice().compareTo(candle.getOpenPrice()) < 0
                || candle.getHighPrice().compareTo(candle.getClosePrice()) < 0
                || candle.getLowPrice().compareTo(candle.getOpenPrice()) > 0
                || candle.getLowPrice().compareTo(candle.getClosePrice()) > 0) {
            throw new IllegalArgumentException("research candle OHLC is inconsistent");
        }
        String identity = candle.getContractCode() + '|' + candle.getPeriod() + '|' + candle.getBarTime();
        if (!identities.add(identity)) {
            throw new IllegalArgumentException("duplicate frozen candle=" + identity);
        }
    }

    private static void validateCompleteCoverage(HtxTrendResearchDatasetFreezeRequest request) {
        long start = request.inputStartAt().atZone(SHANGHAI).toEpochSecond();
        long end = request.inputEndAt().atZone(SHANGHAI).toEpochSecond();
        List<String> contracts = jsonStringArray(request.contractsJson(), "contractsJson");
        List<String> periods = jsonStringArray(request.periodsJson(), "periodsJson");
        Map<String, List<HtxTrendResearchCandleEntity>> grouped = new HashMap<>();
        for (HtxTrendResearchCandleEntity candle : request.candles()) {
            if (candle.getBarTime() < start || candle.getBarTime() >= end) {
                throw new IllegalArgumentException("COMPLETE dataset contains a candle outside its declared window");
            }
            grouped.computeIfAbsent(candle.getContractCode() + '|' + candle.getPeriod(), ignored -> new ArrayList<>()).add(candle);
        }
        for (String contract : contracts) {
            for (String period : periods) {
                Long seconds = PERIOD_SECONDS.get(period);
                if (seconds == null) {
                    throw new IllegalArgumentException("COMPLETE dataset has unsupported period=" + period);
                }
                List<HtxTrendResearchCandleEntity> candles = grouped.get(contract + '|' + period);
                if (candles == null) {
                    throw new IllegalArgumentException("COMPLETE dataset lacks candles for " + contract + ':' + period);
                }
                candles.sort(Comparator.comparing(HtxTrendResearchCandleEntity::getBarTime));
                long expected = start;
                for (HtxTrendResearchCandleEntity candle : candles) {
                    if (candle.getBarTime() != expected) {
                        throw new IllegalArgumentException("COMPLETE dataset has a gap for " + contract + ':' + period);
                    }
                    expected += seconds;
                }
                if (expected != end) {
                    throw new IllegalArgumentException("COMPLETE dataset ends before its declared window for " + contract + ':' + period);
                }
            }
        }
    }

    private static List<String> jsonStringArray(String value, String field) {
        try {
            JsonNode node = JSON.readTree(value);
            if (node == null || !node.isArray() || node.isEmpty()) {
                throw new IllegalArgumentException(field + " must be a non-empty JSON array");
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isTextual() || item.asText().isBlank()) {
                    throw new IllegalArgumentException(field + " must contain non-blank strings only");
                }
                values.add(item.asText());
            }
            return values;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(field + " is not valid JSON", exception);
        }
    }

    private static boolean priceInvalid(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static List<List<HtxTrendResearchCandleEntity>> batches(List<HtxTrendResearchCandleEntity> candles, int size) {
        List<List<HtxTrendResearchCandleEntity>> batches = new ArrayList<>();
        for (int start = 0; start < candles.size(); start += size) {
            batches.add(candles.subList(start, Math.min(start + size, candles.size())));
        }
        return batches;
    }

    private static String freezeProvenance(String sourceProvenance, String status, int storedCandles, String reason) {
        ObjectNode root = JSON.createObjectNode();
        try {
            JsonNode source = blank(sourceProvenance) ? JSON.nullNode() : JSON.readTree(sourceProvenance);
            root.set("source", source == null ? JSON.nullNode() : source);
        } catch (JsonProcessingException ignored) {
            root.put("sourceText", sourceProvenance);
        }
        ObjectNode freeze = root.putObject("freeze");
        freeze.put("status", status);
        freeze.put("storedCandles", storedCandles);
        if (reason != null) {
            freeze.put("failureReason", reason);
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize research dataset provenance", exception);
        }
    }

    private static String rootMessage(Throwable exception) {
        Throwable cursor = exception;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    public record FreezeResult(String datasetKey, String status, int storedCandles, boolean alreadyFrozen) { }

    public static class ResearchDatasetFreezeException extends RuntimeException {
        ResearchDatasetFreezeException(String datasetKey, int storedCandles, Throwable cause) {
            super("research dataset freeze failed datasetKey=" + datasetKey + ", storedCandles=" + storedCandles, cause);
        }
    }
}
