package com.metaverse.coin.strategy.htx.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HtxTrendFrozenExecutionEvidenceTest {
    @TempDir Path directory;

    @Test
    void incompleteHistoricalFactsRemainExplicitAndCannotBeMistakenForTradeMetrics() throws Exception {
        Path evidence = directory.resolve("incomplete.json");
        Files.writeString(evidence, """
                {"schema":"htx-trend-frozen-execution-evidence-v1","sourceKind":"FROZEN_EXECUTION_FACTS",
                 "datasetKey":"research:test","evidenceKey":"execution:test","inputIntegrity":"PARTIAL",
                 "universeComplete":false,"incompleteReason":"historical order fills were never frozen","trades":[]}
                """);
        HtxTrendFrozenExecutionEvidence.Document result = HtxTrendFrozenExecutionEvidence.open(evidence, "research:test");
        assertEquals(false, result.complete());
        assertEquals("historical order fills were never frozen", result.incompleteReason());
    }

    @Test
    void completeDocumentFailsClosedWhenExitEvidenceIsMissing() throws Exception {
        Path evidence = directory.resolve("invalid.json");
        Files.writeString(evidence, """
                {"schema":"htx-trend-frozen-execution-evidence-v1","sourceKind":"FROZEN_EXECUTION_FACTS",
                 "datasetKey":"research:test","evidenceKey":"execution:test","inputIntegrity":"COMPLETE",
                 "universeComplete":true,"trades":[{"runId":"r1","contractCode":"BTC-USDT","period":"15min",
                 "entryBarTime":1,"primaryModel":"FIB_PULLBACK_CONTINUATION","direction":"LONG",
                 "finalGate":{"result":"PASSED","evidenceRef":"gate:1"},"preflight":{"feeProfileRef":"fee:1",
                 "orderBookRef":"book:1","marginRef":"margin:1","leverageRef":"leverage:1","positionStateRef":"position:1"},
                 "entryFill":{"orderId":"entry:1","price":"100","contracts":1,"fee":"0.1"}}]}
                """);
        assertThrows(IllegalArgumentException.class, () -> HtxTrendFrozenExecutionEvidence.open(evidence, "research:test"));
    }
}
