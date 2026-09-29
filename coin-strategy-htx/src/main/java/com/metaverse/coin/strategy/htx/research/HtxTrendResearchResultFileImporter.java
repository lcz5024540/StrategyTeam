package com.metaverse.coin.strategy.htx.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendResearchResultRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendModelEvaluationEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchLabelEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Imports the deterministic output of the P0 offline research pipeline into the immutable research ledger.
 * It is intentionally not a scheduler and never reaches HTX or execution services.
 */
@Service
@RequiredArgsConstructor
public class HtxTrendResearchResultFileImporter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA = "htx-trend-research-derived-ledger-v1";
    private static final String COMPLETE = "COMPLETE";
    private static final int DEFAULT_BATCH_SIZE = 500;

    private final HtxTrendMarketRegimeService regimeService;
    private final HtxTrendResearchResultRecorder resultRecorder;

    public ImportResult importCompleteResult(Path resultPath) {
        try {
            JsonNode root = JSON.readTree(Files.readString(resultPath));
            require(SCHEMA.equals(text(root, "schema")), "unsupported derived research ledger schema");
            require(COMPLETE.equals(text(root, "inputIntegrity")), "only COMPLETE research output may be imported");
            String datasetKey = text(root, "datasetKey");
            ImportCheckpoint checkpoint = checkpoint(resultPath, datasetKey);
            if (checkpoint.completed) {
                return new ImportResult(datasetKey, checkpoint.states, checkpoint.labels, checkpoint.evaluations);
            }
            for (int index = checkpoint.states; index < root.withArray("states").size(); index++) {
                JsonNode value = root.withArray("states").get(index);
                require(datasetKey.equals(text(value, "datasetKey")), "state dataset key differs from ledger");
                regimeService.classifyAndRecord(new HtxTrendMarketRegimeService.MarketRegimeInput(
                        text(value, "snapshotKey"), datasetKey, text(value, "scope"), optionalText(value, "contractCode"),
                        longValue(value, "barTime"), text(value, "dailyDirection"), text(value, "fourHourDirection"),
                        text(value, "oneHourDirection"), decimal(value, "breadthRatio"), decimal(value, "volatilityScore"),
                        decimal(value, "crowdingScore"), JSON.writeValueAsString(value.path("evidence")), COMPLETE));
                checkpoint.states = index + 1;
                checkpoint.saveIfBoundary(resultPath, false);
            }
            for (int index = checkpoint.labels; index < root.withArray("labels").size(); index++) {
                JsonNode value = root.withArray("labels").get(index);
                require(datasetKey.equals(text(value, "datasetKey")), "label dataset key differs from ledger");
                resultRecorder.recordLabel(label(value, datasetKey));
                checkpoint.labels = index + 1;
                checkpoint.saveIfBoundary(resultPath, false);
            }
            for (int index = checkpoint.evaluations; index < root.withArray("evaluations").size(); index++) {
                JsonNode value = root.withArray("evaluations").get(index);
                require(datasetKey.equals(text(value, "datasetKey")), "evaluation dataset key differs from ledger");
                resultRecorder.recordEvaluation(evaluation(value, datasetKey));
                checkpoint.evaluations = index + 1;
                checkpoint.saveIfBoundary(resultPath, false);
            }
            checkpoint.completed = true;
            checkpoint.saveIfBoundary(resultPath, true);
            return new ImportResult(datasetKey, checkpoint.states, checkpoint.labels, checkpoint.evaluations);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read derived research ledger", exception);
        }
    }

    private static ImportCheckpoint checkpoint(Path resultPath, String datasetKey) throws IOException {
        Path checkpointPath = checkpointPath(resultPath);
        String checksum = sha256(resultPath);
        if (!Files.exists(checkpointPath)) {
            return new ImportCheckpoint(datasetKey, checksum);
        }
        JsonNode node = JSON.readTree(Files.readString(checkpointPath));
        if (!datasetKey.equals(text(node, "datasetKey")) || !checksum.equals(text(node, "sourceChecksum"))) {
            throw new IllegalArgumentException("research result checkpoint belongs to a different immutable input");
        }
        return new ImportCheckpoint(datasetKey, checksum, node.path("states").asInt(), node.path("labels").asInt(),
                node.path("evaluations").asInt(), node.path("completed").asBoolean());
    }

    private static Path checkpointPath(Path resultPath) {
        return resultPath.resolveSibling(resultPath.getFileName() + ".checkpoint.json");
    }

    private static String sha256(Path path) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new IllegalStateException("cannot checksum derived research ledger", exception);
        }
    }

    private static final class ImportCheckpoint {
        private final String datasetKey;
        private final String sourceChecksum;
        private int states;
        private int labels;
        private int evaluations;
        private boolean completed;

        private ImportCheckpoint(String datasetKey, String sourceChecksum) {
            this(datasetKey, sourceChecksum, 0, 0, 0, false);
        }

        private ImportCheckpoint(String datasetKey, String sourceChecksum, int states, int labels, int evaluations, boolean completed) {
            this.datasetKey = datasetKey; this.sourceChecksum = sourceChecksum; this.states = states;
            this.labels = labels; this.evaluations = evaluations; this.completed = completed;
        }

        private void saveIfBoundary(Path resultPath, boolean force) throws IOException {
            int total = states + labels + evaluations;
            if (!force && total % DEFAULT_BATCH_SIZE != 0) return;
            Path path = checkpointPath(resultPath);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            JSON.writeValue(temporary.toFile(), java.util.Map.of("datasetKey", datasetKey, "sourceChecksum", sourceChecksum,
                    "states", states, "labels", labels, "evaluations", evaluations, "completed", completed));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static HtxTrendResearchLabelEntity label(JsonNode value, String datasetKey) throws IOException {
        HtxTrendResearchLabelEntity entity = new HtxTrendResearchLabelEntity();
        entity.setLabelKey(text(value, "labelKey")); entity.setDatasetKey(datasetKey);
        entity.setModelCode(text(value, "modelCode")); entity.setModelVersion(text(value, "modelVersion"));
        entity.setContractCode(text(value, "contractCode")); entity.setSide(text(value, "side"));
        entity.setTimeframeProfile(text(value, "timeframeProfile")); entity.setEntryBarTime(longValue(value, "entryBarTime"));
        entity.setEntryPrice(requiredDecimal(value, "entryPrice")); entity.setStopPrice(requiredDecimal(value, "stopPrice"));
        entity.setTargetOneRPrice(decimal(value, "targetOneRPrice")); entity.setTargetTwoRPrice(decimal(value, "targetTwoRPrice"));
        entity.setHorizonBarTime(longValue(value, "horizonBarTime")); entity.setFirstBarrier(text(value, "firstBarrier"));
        entity.setFirstBarrierTime(optionalLong(value, "firstBarrierTime")); entity.setMfe(decimal(value, "mfe"));
        entity.setMae(decimal(value, "mae")); entity.setEstimatedEntryFee(decimal(value, "estimatedEntryFee"));
        entity.setEstimatedExitFee(decimal(value, "estimatedExitFee")); entity.setNetProfit(decimal(value, "netProfit"));
        entity.setAmbiguityReason(optionalText(value, "ambiguityReason")); entity.setDataIntegrity(text(value, "dataIntegrity"));
        entity.setEvidenceJson(JSON.writeValueAsString(value.path("evidence")));
        return entity;
    }

    private static HtxTrendModelEvaluationEntity evaluation(JsonNode value, String datasetKey) throws IOException {
        HtxTrendModelEvaluationEntity entity = new HtxTrendModelEvaluationEntity();
        entity.setEvaluationKey(text(value, "evaluationKey")); entity.setDatasetKey(datasetKey);
        entity.setResearchVersion(text(value, "researchVersion")); entity.setSampleSplit(text(value, "sampleSplit"));
        entity.setModelCode(text(value, "modelCode")); entity.setModelVersion(text(value, "modelVersion"));
        entity.setDirection(optionalText(value, "direction")); entity.setTimeframeProfile(optionalText(value, "timeframeProfile"));
        entity.setMarketState(optionalText(value, "marketState")); entity.setSymbolBucket(optionalText(value, "symbolBucket"));
        entity.setTradeCount(longValue(value, "tradeCount")); entity.setWinCount(longValue(value, "winCount"));
        entity.setLossCount(longValue(value, "lossCount")); entity.setNetProfit(decimal(value, "netProfit"));
        entity.setProfitFactor(decimal(value, "profitFactor")); entity.setExpectancy(decimal(value, "expectancy"));
        entity.setMaxDrawdown(decimal(value, "maxDrawdown")); entity.setFeeTotal(decimal(value, "feeTotal"));
        entity.setDecision(text(value, "decision")); entity.setEvidenceJson(JSON.writeValueAsString(value.path("evidence")));
        return entity;
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        require(value != null && !value.isBlank(), "derived research ledger lacks " + field);
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private static Long longValue(JsonNode node, String field) {
        require(node.hasNonNull(field) && node.path(field).canConvertToLong(), "derived research ledger lacks numeric " + field);
        return node.path(field).asLong();
    }

    private static Long optionalLong(JsonNode node, String field) {
        return node.hasNonNull(field) ? longValue(node, field) : null;
    }

    private static BigDecimal requiredDecimal(JsonNode node, String field) {
        BigDecimal value = decimal(node, field);
        require(value != null, "derived research ledger lacks " + field);
        return value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return node.hasNonNull(field) ? new BigDecimal(node.path(field).asText()) : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record ImportResult(String datasetKey, int states, int labels, int evaluations) { }
}
