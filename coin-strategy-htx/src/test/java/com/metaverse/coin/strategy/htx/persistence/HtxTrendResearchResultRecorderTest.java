package com.metaverse.coin.strategy.htx.persistence;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendModelEvaluationEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchDatasetEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendResearchLabelEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendModelEvaluationMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchDatasetMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendResearchLabelMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HtxTrendResearchResultRecorderTest {

    @Mock HtxTrendResearchDatasetMapper datasetMapper;
    @Mock HtxTrendResearchLabelMapper labelMapper;
    @Mock HtxTrendModelEvaluationMapper evaluationMapper;

    @Test
    void stores_a_label_only_against_an_existing_frozen_dataset() {
        HtxTrendResearchResultRecorder recorder = recorder();
        when(datasetMapper.findByDatasetKey("research:complete")).thenReturn(dataset("COMPLETE"));

        HtxTrendResearchLabelEntity result = recorder.recordLabel(label());

        assertEquals("label:test", result.getLabelKey());
        verify(labelMapper).insert(result);
    }

    @Test
    void refuses_to_promote_a_partial_dataset_even_when_metrics_are_positive() {
        HtxTrendResearchResultRecorder recorder = recorder();
        when(datasetMapper.findByDatasetKey("research:complete")).thenReturn(dataset("PARTIAL"));

        assertThrows(IllegalArgumentException.class, () -> recorder.recordEvaluation(evaluation("PAPER_CANDIDATE")));
    }

    @Test
    void stores_a_rejection_from_a_partial_dataset_without_misrepresenting_it_as_promotion() {
        HtxTrendResearchResultRecorder recorder = recorder();
        when(datasetMapper.findByDatasetKey("research:complete")).thenReturn(dataset("PARTIAL"));

        HtxTrendModelEvaluationEntity result = recorder.recordEvaluation(evaluation("INSUFFICIENT_EVIDENCE"));

        assertEquals("INSUFFICIENT_EVIDENCE", result.getDecision());
        verify(evaluationMapper).insert(result);
    }

    private HtxTrendResearchResultRecorder recorder() {
        return new HtxTrendResearchResultRecorder(datasetMapper, labelMapper, evaluationMapper);
    }

    private static HtxTrendResearchDatasetEntity dataset(String integrity) {
        HtxTrendResearchDatasetEntity entity = new HtxTrendResearchDatasetEntity();
        entity.setStatus("FROZEN");
        entity.setInputIntegrity(integrity);
        return entity;
    }

    private static HtxTrendResearchLabelEntity label() {
        HtxTrendResearchLabelEntity label = new HtxTrendResearchLabelEntity();
        label.setLabelKey("label:test"); label.setDatasetKey("research:complete"); label.setModelCode("DONCHIAN_BREAKOUT");
        label.setModelVersion("v1"); label.setContractCode("BTC-USDT"); label.setSide("LONG"); label.setTimeframeProfile("INTRADAY_15M");
        label.setEntryBarTime(100L); label.setHorizonBarTime(200L); label.setEntryPrice(new BigDecimal("100")); label.setStopPrice(new BigDecimal("90"));
        label.setFirstBarrier("TARGET_1R"); label.setDataIntegrity("COMPLETE"); label.setEvidenceJson("{}");
        return label;
    }

    private static HtxTrendModelEvaluationEntity evaluation(String decision) {
        HtxTrendModelEvaluationEntity evaluation = new HtxTrendModelEvaluationEntity();
        evaluation.setEvaluationKey("evaluation:" + decision); evaluation.setDatasetKey("research:complete"); evaluation.setResearchVersion("v1");
        evaluation.setSampleSplit("VALIDATION"); evaluation.setModelCode("DONCHIAN_BREAKOUT"); evaluation.setModelVersion("v1");
        evaluation.setTradeCount(12L); evaluation.setWinCount(7L); evaluation.setLossCount(5L); evaluation.setDecision(decision); evaluation.setEvidenceJson("{}");
        return evaluation;
    }
}
