package com.metaverse.coin.strategy.htx.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendResearchResultRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HtxTrendResearchResultFileImporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Mock HtxTrendMarketRegimeService regimeService;
    @Mock HtxTrendResearchResultRecorder resultRecorder;

    @Test
    void rejects_partial_derived_output_before_writing_any_research_fact() throws Exception {
        Path file = Files.createTempFile("derived-research", ".json");
        JSON.writeValue(file.toFile(), Map.of("schema", "htx-trend-research-derived-ledger-v1", "datasetKey", "research:test",
                "inputIntegrity", "PARTIAL", "states", List.of(), "labels", List.of(), "evaluations", List.of()));

        assertThrows(IllegalArgumentException.class, () -> importer().importCompleteResult(file));
        verify(regimeService, never()).classifyAndRecord(any());
        verify(resultRecorder, never()).recordLabel(any());
        verify(resultRecorder, never()).recordEvaluation(any());
    }

    @Test
    void imports_complete_state_label_and_out_of_sample_evaluation_under_one_dataset() throws Exception {
        Path file = Files.createTempFile("derived-research", ".json");
        Files.writeString(file, """
                {"schema":"htx-trend-research-derived-ledger-v1","datasetKey":"research:test","inputIntegrity":"COMPLETE",
                 "states":[{"snapshotKey":"state:test","datasetKey":"research:test","scope":"CONTRACT","contractCode":"BTC-USDT","barTime":1000,"dailyDirection":"LONG","fourHourDirection":"LONG","oneHourDirection":"LONG","evidence":{"rule":"v1"}}],
                 "labels":[{"labelKey":"label:test","datasetKey":"research:test","modelCode":"DONCHIAN_BREAKOUT","modelVersion":"v1","contractCode":"BTC-USDT","side":"LONG","timeframeProfile":"INTRADAY_15M","entryBarTime":1000,"entryPrice":"100","stopPrice":"95","horizonBarTime":2000,"firstBarrier":"TARGET_1R","dataIntegrity":"COMPLETE","evidence":{"costsIncluded":true}}],
                 "evaluations":[{"evaluationKey":"evaluation:test","datasetKey":"research:test","researchVersion":"research-engine-v1","sampleSplit":"OUT_OF_SAMPLE","modelCode":"DONCHIAN_BREAKOUT","modelVersion":"v1","tradeCount":30,"winCount":18,"lossCount":12,"decision":"PAPER_CANDIDATE","evidence":{"costsIncluded":true}}]}
                """);

        HtxTrendResearchResultFileImporter.ImportResult result = importer().importCompleteResult(file);

        assertEquals(new HtxTrendResearchResultFileImporter.ImportResult("research:test", 1, 1, 1), result);
        verify(regimeService).classifyAndRecord(any());
        verify(resultRecorder).recordLabel(any());
        verify(resultRecorder).recordEvaluation(any());
    }

    private HtxTrendResearchResultFileImporter importer() {
        return new HtxTrendResearchResultFileImporter(regimeService, resultRecorder);
    }
}
