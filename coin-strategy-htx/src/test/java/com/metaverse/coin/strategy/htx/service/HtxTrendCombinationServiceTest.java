package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.HtxTrendCandidateSignalRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCombinationRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HtxTrendCombinationServiceTest {

    @Test
    void marketSnapshotPersistsBreadthAndAnchorContext() {
        HtxTrendStrategyPolicyService policyService = mock(HtxTrendStrategyPolicyService.class);
        HtxTrendCombinationRecorder combinationRecorder = mock(HtxTrendCombinationRecorder.class);
        HtxTrendCandidateSignalRecorder candidateRecorder = mock(HtxTrendCandidateSignalRecorder.class);
        when(combinationRecorder.recordSnapshot(any())).thenReturn(1L);
        HtxTrendCombinationService service = new HtxTrendCombinationService(policyService, combinationRecorder,
                candidateRecorder);

        service.recordMarketSnapshot(1_790_069_400L, "BROAD_UP", 52L, 44L, 2L, "broad up", "BROAD_UP",
                6, 4, 2, "BTC and ETH aligned");

        ArgumentCaptor<HtxTrendFactorSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendFactorSnapshotEntity.class);
        verify(combinationRecorder).recordSnapshot(snapshot.capture());
        assertEquals("MARKET:15min:1790069400:trend-market-v1", snapshot.getValue().getSnapshotKey());
        assertEquals("MARKET", snapshot.getValue().getContractCode());
        assertEquals("BROAD_UP", snapshot.getValue().getMarketRegime());
        assertTrue(snapshot.getValue().getFactorsJson().contains("\"total\":52"));
        assertTrue(snapshot.getValue().getFactorsJson().contains("BTC and ETH aligned"));
    }

    @Test
    void multiTimeframeSnapshotKeepsNumericFactorsAndUnavailablePeriods() {
        HtxTrendStrategyPolicyService policyService = mock(HtxTrendStrategyPolicyService.class);
        HtxTrendCombinationRecorder combinationRecorder = mock(HtxTrendCombinationRecorder.class);
        HtxTrendCandidateSignalRecorder candidateRecorder = mock(HtxTrendCandidateSignalRecorder.class);
        when(combinationRecorder.recordSnapshot(any())).thenReturn(1L);
        HtxTrendCombinationService service = new HtxTrendCombinationService(policyService, combinationRecorder,
                candidateRecorder);

        service.recordMultiTimeframeSnapshot("BTC-USDT", "15min", 1_790_069_400L, "BROAD_UP",
                List.of(signal("15min"), TrendCandidateSignal.skip("1day", "history kline unavailable")));

        ArgumentCaptor<HtxTrendFactorSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendFactorSnapshotEntity.class);
        verify(combinationRecorder).recordSnapshot(snapshot.capture());
        assertEquals("BTC-USDT:15min:1790069400:trend-factor-multi-v1", snapshot.getValue().getSnapshotKey());
        assertTrue(snapshot.getValue().getFactorsJson().contains("\"roc\":0.02"));
        assertTrue(snapshot.getValue().getFactorsJson().contains("\"status\":\"UNAVAILABLE\""));
        assertTrue(snapshot.getValue().getFactorsJson().contains("history kline unavailable"));
    }

    @Test
    void multiTimeframeSnapshotRoundsEvidenceWithoutChangingItsNumericType() {
        HtxTrendStrategyPolicyService policyService = mock(HtxTrendStrategyPolicyService.class);
        HtxTrendCombinationRecorder combinationRecorder = mock(HtxTrendCombinationRecorder.class);
        HtxTrendCandidateSignalRecorder candidateRecorder = mock(HtxTrendCandidateSignalRecorder.class);
        when(combinationRecorder.recordSnapshot(any())).thenReturn(1L);
        HtxTrendCombinationService service = new HtxTrendCombinationService(policyService, combinationRecorder,
                candidateRecorder);

        TrendCandidateSignal precise = new TrendCandidateSignal("15min", 1_790_069_400L, Direction.LONG,
                Regime.TRENDING_UP, new BigDecimal("90"), new BigDecimal("90"), BigDecimal.ZERO,
                new BigDecimal("90"), new BigDecimal("0.1234567890125678"),
                new BigDecimal("0.1234567890125678"), new BigDecimal("0.1234567890125678"),
                new BigDecimal("0.1234567890125678"), new BigDecimal("101"), new BigDecimal("95"),
                new BigDecimal("2"), new BigDecimal("0.02"), new BigDecimal("30"),
                new BigDecimal("0.02"), new BigDecimal("1.5"), "valid", "none");

        service.recordMultiTimeframeSnapshot("BTC-USDT", "15min", 1_790_069_400L, "BROAD_UP",
                List.of(precise));

        ArgumentCaptor<HtxTrendFactorSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendFactorSnapshotEntity.class);
        verify(combinationRecorder).recordSnapshot(snapshot.capture());
        assertTrue(snapshot.getValue().getFactorsJson().contains("\"fast\":0.123456789013"));
        assertFalse(snapshot.getValue().getFactorsJson().contains("0.1234567890125678"));
    }

    @Test
    void entryCostEvidencePersistsActualFeesOrderBookAndNetExpectation() {
        HtxTrendStrategyPolicyService policyService = mock(HtxTrendStrategyPolicyService.class);
        HtxTrendCombinationRecorder combinationRecorder = mock(HtxTrendCombinationRecorder.class);
        HtxTrendCandidateSignalRecorder candidateRecorder = mock(HtxTrendCandidateSignalRecorder.class);
        when(combinationRecorder.recordSnapshot(any())).thenReturn(1L);
        HtxTrendCombinationService service = new HtxTrendCombinationService(policyService, combinationRecorder,
                candidateRecorder);

        service.recordEntryCostEvidence("BTC-USDT", "15min", 1_790_069_400L, "combo-v2", "BROAD_UP",
                new HtxTrendCombinationService.EntryCostEvidence("primary", "HTX_ACCOUNT",
                        new BigDecimal("0.0002"), new BigDecimal("0.0004"), new BigDecimal("0.0004"),
                        new BigDecimal("100"), new BigDecimal("100.1"), new BigDecimal("0.001"), 5,
                        new BigDecimal("900"), new BigDecimal("600"), new BigDecimal("1.5"), "LONG",
                        new BigDecimal("100.1"), 3L, new BigDecimal("300.3"), new BigDecimal("1.2"),
                        new BigDecimal("3.6"), new BigDecimal("0.12012"), new BigDecimal("0.12012"),
                        new BigDecimal("0.3003"), new BigDecimal("3.05946"), new BigDecimal("0.22022"),
                        "PASSED", "all cost gates passed"));

        ArgumentCaptor<HtxTrendFactorSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendFactorSnapshotEntity.class);
        verify(combinationRecorder).recordSnapshot(snapshot.capture());
        assertEquals("BTC-USDT:15min:1790069400:combo-v2:entry-cost-v1", snapshot.getValue().getSnapshotKey());
        assertTrue(snapshot.getValue().getFactorsJson().contains("\"feeSource\":\"HTX_ACCOUNT\""));
        assertTrue(snapshot.getValue().getFactorsJson().contains("\"spreadRate\":0.001"));
        assertTrue(snapshot.getValue().getFactorsJson().contains("\"netPnlAfterCosts\":3.05946"));
    }

    private static TrendCandidateSignal signal(String period) {
        return new TrendCandidateSignal(period, 1_790_069_400L, Direction.LONG, Regime.TRENDING_UP,
                new BigDecimal("90"), new BigDecimal("90"), BigDecimal.ZERO, new BigDecimal("90"),
                new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("97"),
                new BigDecimal("101"), new BigDecimal("95"), new BigDecimal("2"), new BigDecimal("0.02"),
                new BigDecimal("30"), new BigDecimal("0.02"), new BigDecimal("1.5"), "valid", "none");
    }
}
