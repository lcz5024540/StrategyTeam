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

    private final HtxTrendMarketRegimeService regimeService;
    private final HtxTrendResearchResultRecorder resultRecorder;

    public ImportResult importCompleteResult(Path resultPath) {
        try {
            JsonNode root = JSON.readTree(Files.readString(resultPath));
            require(SCHEMA.equals(text(root, "schema")), "unsupported derived research ledger schema");
            require(COMPLETE.equals(text(root, "inputIntegrity")), "only COMPLETE research output may be imported");
            String datasetKey = text(root, "datasetKey");
            int states = 0, labels = 0, evaluations = 0;
            for (JsonNode value : root.withArray("states")) {
                require(datasetKey.equals(text(value, "datasetKey")), "state dataset key differs from ledger");
                regimeService.classifyAndRecord(new HtxTrendMarketRegimeService.MarketRegimeInput(
                        text(value, "snapshotKey"), datasetKey, text(value, "scope"), optionalText(value, "contractCode"),
                        longValue(value, "barTime"), text(value, "dailyDirection"), text(value, "fourHourDirection"),
                        text(value, "oneHourDirection"), decimal(value, "breadthRatio"), decimal(value, "volatilityScore"),
                        decimal(value, "crowdingScore"), JSON.writeValueAsString(value.path("evidence")), COMPLETE));
                states++;
            }
            for (JsonNode value : root.withArray("labels")) {
                require(datasetKey.equals(text(value, "datasetKey")), "label dataset key differs from ledger");
                resultRecorder.recordLabel(label(value, datasetKey));
                labels++;
            }
            for (JsonNode value : root.withArray("evaluations")) {
                require(datasetKey.equals(text(value, "datasetKey")), "evaluation dataset key differs from ledger");
                resultRecorder.recordEvaluation(evaluation(value, datasetKey));
                evaluations++;
            }
            return new ImportResult(datasetKey, states, labels, evaluations);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read derived research ledger", exception);
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
