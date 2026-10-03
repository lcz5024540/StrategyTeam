package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtxTrendModelContractTest {

    @Test
    void executableModelsKeepDistinctEntryAndExitResponsibilities() {
        HtxTrendModelContract donchian = HtxTrendModelContract.fromCode("DONCHIAN_BREAKOUT");
        HtxTrendModelContract fibonacci = HtxTrendModelContract.fromCode("FIB_PULLBACK_CONTINUATION");
        HtxTrendModelContract momentum = HtxTrendModelContract.fromCode("EMA_VWAP_MOMENTUM_CONTINUATION");

        assertTrue(donchian.appliesTo(Regime.TRENDING_UP, "15min"));
        assertEquals(HtxTrendModelContract.TargetBasis.CHANNEL_MEASURED_MOVE, donchian.targetBasis());
        assertEquals(HtxTrendModelContract.ExitAuthority.NOT_YET_AUTHORIZED, donchian.exitAuthority());
        assertEquals(HtxTrendModelContract.EntryStyle.PASSIVE_LIMIT_PREFERRED, fibonacci.preferredEntryStyle());
        assertEquals(HtxTrendModelContract.ExitAuthority.FIB_V4, fibonacci.exitAuthority());
        assertEquals(HtxTrendModelContract.InvalidationBasis.EMA_VWAP_FAILURE, momentum.invalidationBasis());
        assertEquals(HtxTrendModelContract.TargetBasis.STRUCTURE_SWING, momentum.targetBasis());
    }

    @Test
    void aContractDoesNotSilentlyPromoteASecondTimeframeOrSupportingEvidence() {
        HtxTrendModelContract donchian = HtxTrendModelContract.fromCode("DONCHIAN_BREAKOUT");
        HtxTrendModelContract pullbackEvidence = HtxTrendModelContract.fromCode("EMA_VWAP_PULLBACK");

        assertFalse(donchian.appliesTo(Regime.TRENDING_UP, "4hour"));
        assertFalse(pullbackEvidence.appliesTo(Regime.TRENDING_UP, "15min"));
        assertEquals(HtxTrendModelContract.EntryRole.SUPPORTING_EVIDENCE, pullbackEvidence.entryRole());
    }
}
