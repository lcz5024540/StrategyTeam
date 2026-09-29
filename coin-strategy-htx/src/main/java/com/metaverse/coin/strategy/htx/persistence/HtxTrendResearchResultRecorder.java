package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendModelEvaluationEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchDatasetEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchLabelEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendModelEvaluationMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchDatasetMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchLabelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/** Persists immutable label and evaluation facts after an offline research run has completed. */
@Component
@RequiredArgsConstructor
public class HtxTrendResearchResultRecorder {

    private static final Set<String> LABEL_BARRIERS = Set.of("TARGET_1R", "TARGET_2R", "STOP", "TIMEOUT", "UNAVAILABLE");
    private static final Set<String> EVALUATION_DECISIONS = Set.of("PAPER_CANDIDATE", "REJECT", "INSUFFICIENT_SAMPLE", "INSUFFICIENT_EVIDENCE");

    private final HtxTrendResearchDatasetMapper datasetMapper;
    private final HtxTrendResearchLabelMapper labelMapper;
    private final HtxTrendModelEvaluationMapper evaluationMapper;

    public HtxTrendResearchLabelEntity recordLabel(HtxTrendResearchLabelEntity label) {
        validateLabel(label);
        HtxTrendResearchLabelEntity existing = labelMapper.findByLabelKey(label.getLabelKey());
        if (existing != null) {
            return existing;
        }
        requireFrozenDataset(label.getDatasetKey());
        labelMapper.insert(label);
        return label;
    }

    public HtxTrendModelEvaluationEntity recordEvaluation(HtxTrendModelEvaluationEntity evaluation) {
        validateEvaluation(evaluation);
        HtxTrendModelEvaluationEntity existing = evaluationMapper.findByEvaluationKey(evaluation.getEvaluationKey());
        if (existing != null) {
            return existing;
        }
        HtxTrendResearchDatasetEntity dataset = requireFrozenDataset(evaluation.getDatasetKey());
        if ("PAPER_CANDIDATE".equals(evaluation.getDecision()) && !"COMPLETE".equals(dataset.getInputIntegrity())) {
            throw new IllegalArgumentException("partial or insufficient research input cannot promote a model");
        }
        evaluationMapper.insert(evaluation);
        return evaluation;
    }

    private HtxTrendResearchDatasetEntity requireFrozenDataset(String datasetKey) {
        HtxTrendResearchDatasetEntity dataset = datasetMapper.findByDatasetKey(datasetKey);
        if (dataset == null || !HtxTrendResearchDatasetRecorder.FROZEN.equals(dataset.getStatus())) {
            throw new IllegalArgumentException("research result requires an existing FROZEN dataset");
        }
        return dataset;
    }

    private static void validateLabel(HtxTrendResearchLabelEntity label) {
        if (label == null || blank(label.getLabelKey()) || blank(label.getDatasetKey()) || blank(label.getModelCode())
                || blank(label.getModelVersion()) || blank(label.getContractCode()) || !side(label.getSide())
                || blank(label.getTimeframeProfile()) || label.getEntryBarTime() == null || label.getHorizonBarTime() == null
                || label.getHorizonBarTime() < label.getEntryBarTime() || invalidPrice(label.getEntryPrice())
                || invalidPrice(label.getStopPrice()) || !LABEL_BARRIERS.contains(label.getFirstBarrier())
                || blank(label.getDataIntegrity()) || blank(label.getEvidenceJson())) {
            throw new IllegalArgumentException("research label is incomplete");
        }
    }

    private static void validateEvaluation(HtxTrendModelEvaluationEntity evaluation) {
        if (evaluation == null || blank(evaluation.getEvaluationKey()) || blank(evaluation.getDatasetKey())
                || blank(evaluation.getResearchVersion()) || blank(evaluation.getSampleSplit()) || blank(evaluation.getModelCode())
                || blank(evaluation.getModelVersion()) || evaluation.getTradeCount() == null || evaluation.getTradeCount() < 0
                || evaluation.getWinCount() == null || evaluation.getWinCount() < 0 || evaluation.getLossCount() == null
                || evaluation.getLossCount() < 0 || evaluation.getWinCount() + evaluation.getLossCount() > evaluation.getTradeCount()
                || !EVALUATION_DECISIONS.contains(evaluation.getDecision()) || blank(evaluation.getEvidenceJson())) {
            throw new IllegalArgumentException("research evaluation is incomplete");
        }
    }

    private static boolean side(String value) {
        return "LONG".equals(value) || "SHORT".equals(value);
    }

    private static boolean invalidPrice(BigDecimal value) {
        return value == null || value.signum() <= 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
