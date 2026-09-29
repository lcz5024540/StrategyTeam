package com.metaverse.coin.strategy.htx.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendResearchDatasetFreezeRequest;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendResearchDatasetRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchCandleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Converts a locally frozen, content-addressed collector output into the immutable research ledger.
 *
 * <p>This service is intentionally file-to-ledger only: it does not call HTX, read account credentials,
 * or interact with a strategy execution service. The caller must provide a COMPLETE manifest; a partial
 * capture is rejected before any database write is attempted.</p>
 */
@Service
@RequiredArgsConstructor
public class HtxTrendResearchDatasetFileImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String MANIFEST_SCHEMA = "htx-trend-research-dataset-manifest-v1";
    private static final String COMPLETE = "COMPLETE";
    private static final String RAW_PUBLIC = "RAW_PUBLIC";
    private static final String RESEARCH_PURPOSE = "RESEARCH";

    private final HtxTrendResearchDatasetRecorder datasetRecorder;

    public HtxTrendResearchDatasetRecorder.FreezeResult importCompleteDataset(Path dataDirectory, Path manifestPath) {
        return datasetRecorder.freeze(readCompleteRequest(dataDirectory, manifestPath));
    }

    static HtxTrendResearchDatasetFreezeRequest readCompleteRequest(Path dataDirectory, Path manifestPath) {
        try {
            JsonNode manifest = JSON.readTree(Files.readString(manifestPath));
            validateManifest(manifest);
            List<HtxTrendResearchCandleEntity> candles = new ArrayList<>();
            for (JsonNode input : manifest.withArray("inputs")) {
                candles.addAll(readAndVerifyInput(dataDirectory, input, manifest));
            }
            return new HtxTrendResearchDatasetFreezeRequest(
                    requiredText(manifest, "datasetKey"),
                    "FROZEN_PUBLIC_KLINE",
                    manifestPath.getFileName().toString(),
                    LocalDateTime.now(SHANGHAI),
                    localDateTime(requiredText(manifest, "inputStartAt")),
                    localDateTime(requiredText(manifest, "inputEndAt")),
                    JSON.writeValueAsString(manifest.withArray("contracts")),
                    JSON.writeValueAsString(manifest.withArray("periods")),
                    requiredText(manifest, "inputChecksum"),
                    COMPLETE,
                    RESEARCH_PURPOSE,
                    JSON.writeValueAsString(manifest),
                    candles);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read frozen research dataset files", exception);
        }
    }

    private static void validateManifest(JsonNode manifest) {
        if (!MANIFEST_SCHEMA.equals(requiredText(manifest, "schema"))) {
            throw new IllegalArgumentException("unsupported research manifest schema");
        }
        if (!COMPLETE.equals(requiredText(manifest, "inputIntegrity"))) {
            throw new IllegalArgumentException("only COMPLETE frozen research manifests may be imported");
        }
        if (!manifest.path("contracts").isArray() || manifest.path("contracts").isEmpty()
                || !manifest.path("periods").isArray() || manifest.path("periods").isEmpty()
                || !manifest.path("inputs").isArray() || manifest.path("inputs").isEmpty()) {
            throw new IllegalArgumentException("research manifest has an incomplete universe");
        }
    }

    private static List<HtxTrendResearchCandleEntity> readAndVerifyInput(Path dataDirectory, JsonNode input, JsonNode manifest)
            throws IOException {
        String contract = requiredText(input, "contract");
        String period = requiredText(input, "period");
        String sourceKind = requiredText(input, "sourceKind");
        if (!RAW_PUBLIC.equals(sourceKind)) {
            throw new IllegalArgumentException("import requires a materialized RAW_PUBLIC input: " + contract + ':' + period);
        }
        Path inputPath = dataDirectory.resolve(requiredText(input, "file")).normalize();
        if (!inputPath.startsWith(dataDirectory.normalize()) || !Files.isRegularFile(inputPath)) {
            throw new IllegalArgumentException("research manifest input file is unavailable: " + inputPath);
        }
        if (!requiredText(input, "sha256").equals(sha256(inputPath))) {
            throw new IllegalArgumentException("research input checksum differs from manifest: " + inputPath.getFileName());
        }
        JsonNode data = JSON.readTree(Files.readString(inputPath)).path("data");
        if (!data.isArray()) {
            throw new IllegalArgumentException("research input has no Kline data array: " + inputPath.getFileName());
        }
        List<HtxTrendResearchCandleEntity> candles = new ArrayList<>();
        long start = epoch(requiredText(manifest, "inputStartAt"));
        long end = epoch(requiredText(manifest, "inputEndAt"));
        for (JsonNode bar : data) {
            long barTime = requiredLong(bar, "id");
            if (barTime < start || barTime >= end) {
                throw new IllegalArgumentException("research input contains a candle outside the manifest window");
            }
            candles.add(candle(requiredText(manifest, "datasetKey"), contract, period, bar));
        }
        candles.sort(Comparator.comparing(HtxTrendResearchCandleEntity::getBarTime));
        if (candles.size() != input.path("count").asInt(-1)
                || candles.isEmpty() || candles.get(0).getBarTime() != input.path("firstBarTime").asLong(-1)
                || candles.get(candles.size() - 1).getBarTime() != input.path("lastBarTime").asLong(-1)) {
            throw new IllegalArgumentException("research input bars differ from manifest: " + contract + ':' + period);
        }
        return candles;
    }

    private static HtxTrendResearchCandleEntity candle(String datasetKey, String contract, String period, JsonNode bar) {
        HtxTrendResearchCandleEntity entity = new HtxTrendResearchCandleEntity();
        entity.setDatasetKey(datasetKey);
        entity.setContractCode(contract);
        entity.setPeriod(period);
        entity.setBarTime(requiredLong(bar, "id"));
        entity.setOpenPrice(decimal(bar, "open"));
        entity.setHighPrice(decimal(bar, "high"));
        entity.setLowPrice(decimal(bar, "low"));
        entity.setClosePrice(decimal(bar, "close"));
        entity.setVolume(optionalDecimal(bar, "vol"));
        entity.setTurnover(optionalDecimal(bar, "trade_turnover"));
        entity.setTradeCount(bar.hasNonNull("count") ? requiredLong(bar, "count") : null);
        entity.setSourceKind(RAW_PUBLIC);
        return entity;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("research manifest lacks " + field);
        }
        return value;
    }

    private static long requiredLong(JsonNode node, String field) {
        if (!node.hasNonNull(field) || !node.path(field).canConvertToLong()) {
            throw new IllegalArgumentException("research Kline lacks numeric " + field);
        }
        return node.path(field).asLong();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new IllegalArgumentException("research Kline lacks " + field);
        }
        return new BigDecimal(node.path(field).asText());
    }

    private static BigDecimal optionalDecimal(JsonNode node, String field) {
        return node.hasNonNull(field) ? new BigDecimal(node.path(field).asText()) : null;
    }

    private static LocalDateTime localDateTime(String value) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch(value)), SHANGHAI);
    }

    private static long epoch(String value) {
        return java.time.OffsetDateTime.parse(value + "+08:00").toEpochSecond();
    }

    private static String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("cannot calculate research input checksum", exception);
        }
    }
}
