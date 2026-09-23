package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCandidateSignalRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCombinationRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.service.HtxTrendCombinationCalculator.CombinationCandidate;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    @Test
    void factorSnapshotSeparatesSameStructureConfirmationsFromIndependentAlpha() {
        HtxTrendCombinationCalculator calculator = new HtxTrendCombinationCalculator();
        List<HtxSwapKline> candles = risingRange(32);
        candles.add(candle(32, "106", "121", "107", "120"));
        for (int index = 33; index < 40; index++) {
            candles.add(candle(index, "116", "120", "112", "116"));
        }
        candles.add(candle(40, "115", "117", "113", "114"));
        CombinationCandidate candidate = calculator.evaluate(candles,
                signal("15min", "114", "120"), 32).get(0);

        JSONObject snapshot = JSON.parseObject(HtxTrendCombinationService.factorSnapshotJson(signal("15min", "114", "120"),
                candidate));
        JSONObject trendState = snapshot.getJSONObject("trendState");
        JSONObject evidence = snapshot.getJSONObject("modelEvidence");
        JSONArray confirmations = evidence.getJSONArray("confirmations");

        assertEquals("trend-factor-v3", snapshot.getString("schema"));
        assertEquals("AVAILABLE", trendState.getJSONObject("trendStrength").getString("status"));
        assertEquals("NOT_AVAILABLE", trendState.getJSONObject("derivativesQuality").getString("status"));
        assertEquals("NOT_EVALUATED", trendState.getJSONObject("executionQuality").getString("status"));
        assertEquals(1, evidence.getIntValue("independentAlphaCount"));
        assertEquals("BREAKOUT_RETEST:32", evidence.getJSONObject("primary").getString("correlationGroup"));
        assertFalse(confirmations.isEmpty());
        for (Object confirmation : confirmations) {
            JSONObject item = (JSONObject) confirmation;
            assertEquals("CONFIRMATION_SAME_STRUCTURE", item.getString("role"));
            assertEquals("BREAKOUT_RETEST:32", item.getString("correlationGroup"));
        }
    }

    @Test
    void rejectedSnapshotKeepsUnavailableAndNotEvaluatedEvidenceExplicit() {
        JSONObject snapshot = JSON.parseObject(HtxTrendCombinationService.factorSnapshotJson(signal("15min", "100", "101"), null));

        assertEquals("NOT_EVALUATED", snapshot.getJSONObject("trendState")
                .getJSONObject("structureQuality").getString("status"));
        assertEquals("NOT_AVAILABLE", snapshot.getJSONObject("trendState")
                .getJSONObject("crowdingRisk").getString("status"));
        assertEquals("NOT_EVALUATED", snapshot.getJSONObject("modelEvidence").getString("status"));
        assertEquals(0, snapshot.getJSONObject("modelEvidence").getIntValue("independentAlphaCount"));
    }

    @Test
    void pricePlanPersistsWorstPriceRiskTargetsAndTimeframeProfile() {
        HtxTrendCombinationRecorder combinationRecorder = mock(HtxTrendCombinationRecorder.class);
        when(combinationRecorder.recordSnapshot(any())).thenReturn(1L);
        HtxTrendCombinationService service = new HtxTrendCombinationService(mock(HtxTrendStrategyPolicyService.class),
                combinationRecorder, mock(HtxTrendCandidateSignalRecorder.class));

        service.recordPricePlan("BTC-USDT", "15min", 1_790_069_400L, "combo-v2", "BROAD_UP",
                new HtxTrendCombinationService.PricePlanEvidence("run-1", "INTRADAY_15M",
                        new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("101"), 1_790_070_345L,
                        new BigDecimal("98"), new BigDecimal("98"), new BigDecimal("104"), new BigDecimal("107"),
                        new BigDecimal("108"), new BigDecimal("3"), new BigDecimal("1.8")));

        ArgumentCaptor<HtxTrendFactorSnapshotEntity> snapshot = ArgumentCaptor.forClass(HtxTrendFactorSnapshotEntity.class);
        verify(combinationRecorder, times(2)).recordSnapshot(snapshot.capture());
        JSONObject json = JSON.parseObject(snapshot.getAllValues().get(0).getFactorsJson());
        JSONObject event = JSON.parseObject(snapshot.getAllValues().get(1).getFactorsJson());
        assertEquals("trend-price-plan-v1", json.getString("schema"));
        assertEquals("INTRADAY_15M", json.getString("timeframeProfile"));
        assertEquals(new BigDecimal("101"), json.getJSONObject("entry").getBigDecimal("maxAcceptablePrice"));
        assertEquals(new BigDecimal("107"), json.getJSONObject("exit").getBigDecimal("target2RPrice"));
        assertEquals(0, json.getJSONObject("risk").getIntValue("maxSlippageBps"));
        assertEquals("ENTRY_READY", event.getString("eventType"));
        assertEquals("NO_EXCHANGE_ORDER", event.getString("executionBoundary"));
    }

    private static TrendCandidateSignal signal(String period) {
        return signal(period, "100", "101");
    }

    private static TrendCandidateSignal signal(String period, String close, String breakoutHigh) {
        return new TrendCandidateSignal(period, 1_790_069_400L, Direction.LONG, Regime.TRENDING_UP,
                new BigDecimal("90"), new BigDecimal("90"), BigDecimal.ZERO, new BigDecimal("90"),
                new BigDecimal(close), new BigDecimal("99"), new BigDecimal("98"), new BigDecimal("97"),
                new BigDecimal(breakoutHigh), new BigDecimal("95"), new BigDecimal("2"), new BigDecimal("0.02"),
                new BigDecimal("30"), new BigDecimal("0.02"), new BigDecimal("1.5"), "valid", "none");
    }

    private static List<HtxSwapKline> risingRange(int size) {
        List<HtxSwapKline> rows = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            rows.add(candle(index, "100", "110", "101", "105"));
        }
        return rows;
    }

    private static HtxSwapKline candle(long id, String open, String high, String low, String close) {
        HtxSwapKline candle = new HtxSwapKline();
        candle.setId(id);
        candle.setOpen(new BigDecimal(open));
        candle.setHigh(new BigDecimal(high));
        candle.setLow(new BigDecimal(low));
        candle.setClose(new BigDecimal(close));
        candle.setAmount(BigDecimal.ONE);
        return candle;
    }
}
