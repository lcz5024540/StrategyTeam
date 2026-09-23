package com.metaverse.coin.strategy.htx.service;

import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPaperExecutionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class HtxTrendPaperExecutionServiceTest {
    private static final String RUN_ID = "trend-entry:BTC-USDT:15min:100";
    private static final long BAR_TIME = 100L;

    private HtxTrendCombinationService combinationService;
    private HtxTrendPaperExecutionMapper mapper;
    private HtxTrendPaperExecutionService service;

    @BeforeEach
    void setUp() {
        combinationService = mock(HtxTrendCombinationService.class);
        mapper = mock(HtxTrendPaperExecutionMapper.class);
        service = new HtxTrendPaperExecutionService(combinationService, mapper);
        when(combinationService.latestExternalSignal("BTC-USDT", "15min", BAR_TIME, "combo-v2", RUN_ID))
                .thenReturn(snapshot("trend-external-signal-v1", "{}"));
        when(combinationService.latestPricePlan("BTC-USDT", "15min", BAR_TIME, "combo-v2", RUN_ID))
                .thenReturn(pricePlan("LONG", "100", 4L, Instant.now().plusSeconds(300L).getEpochSecond()));
        when(combinationService.recordPaperExitPlan(eq("BTC-USDT"), eq("15min"), eq(BAR_TIME), eq("combo-v2"),
                eq("BROAD_UP"), any())).thenReturn(77L);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            HtxTrendPaperExecutionEntity entity = invocation.getArgument(0);
            entity.setId(88L);
            return 1;
        });
    }

    @Test
    void rejectsCallbackWhenOutboxEventIsMissing() {
        when(combinationService.latestExternalSignal("BTC-USDT", "15min", BAR_TIME, "combo-v2", RUN_ID)).thenReturn(null);

        HtxTrendPaperExecutionService.Result result = service.report(filled("missing-outbox", "100", 4L));

        assertFalse(result.accepted());
        assertEquals("ENTRY_READY outbox event not found", result.reason());
        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsDuplicateExecutionKeyBeforeWritingAnyPaperPlan() {
        HtxTrendPaperExecutionEntity existing = new HtxTrendPaperExecutionEntity();
        existing.setId(1L);
        when(mapper.findByExecutionKey("duplicate")).thenReturn(existing);

        HtxTrendPaperExecutionService.Result result = service.report(filled("duplicate", "100", 4L));

        assertFalse(result.accepted());
        assertEquals("duplicate paper execution key", result.reason());
        verifyNoMoreInteractions(combinationService);
        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsFilledOutcomeWithoutPriceAndContracts() {
        HtxTrendPaperExecutionService.Result result = service.report(new HtxTrendPaperExecutionService.Report(
                "missing-fill", RUN_ID, "BTC-USDT", "15min", BAR_TIME, "combo-v2", "FILLED", null, null, ""));

        assertFalse(result.accepted());
        assertEquals("fill price and filled contracts are required for a filled outcome", result.reason());
        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsLongFillAboveTheFrozenPriceBoundary() {
        HtxTrendPaperExecutionService.Result result = service.report(filled("slippage", "100.01", 4L));

        assertFalse(result.accepted());
        assertEquals("long fill exceeds max acceptable price", result.reason());
        verify(combinationService, never()).recordPaperExitPlan(any(), any(), any(), any(), any(), any());
        verify(mapper, never()).insert(any());
    }

    @Test
    void recalculatesExitPlanFromPartialFillAndNeverDependsOnExchangeOrderService() {
        HtxTrendPaperExecutionService.Result result = service.report(new HtxTrendPaperExecutionService.Report(
                "partial", RUN_ID, "BTC-USDT", "15min", BAR_TIME, "combo-v2", "PARTIALLY_FILLED",
                new BigDecimal("99"), 2L, "passive limit partially filled"));

        assertTrue(result.accepted());
        assertEquals(88L, result.id());
        ArgumentCaptor<HtxTrendCombinationService.PaperExitPlanEvidence> plan = ArgumentCaptor.forClass(
                HtxTrendCombinationService.PaperExitPlanEvidence.class);
        verify(combinationService).recordPaperExitPlan(eq("BTC-USDT"), eq("15min"), eq(BAR_TIME), eq("combo-v2"),
                eq("BROAD_UP"), plan.capture());
        assertDecimalEquals("4", plan.getValue().riskDistance());
        assertDecimalEquals("0.8", plan.getValue().plannedRiskUsdt());
        assertDecimalEquals("103", plan.getValue().target1RPrice());
        assertDecimalEquals("107", plan.getValue().target2RPrice());
        assertDecimalEquals("2.2", plan.getValue().structureGrossPnlUsdt());
        assertDecimalEquals("0.2", plan.getValue().estimatedRoundTripCostUsdt());
        assertDecimalEquals("2.5", plan.getValue().netRAtStructureTarget());
        assertTrue(Arrays.stream(HtxTrendPaperExecutionService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().contains("HtxUsdtSwap")));
    }

    @Test
    void acceptsExpiredOutcomeButRejectsLateFill() {
        when(combinationService.latestPricePlan("BTC-USDT", "15min", BAR_TIME, "combo-v2", RUN_ID))
                .thenReturn(pricePlan("LONG", "100", 4L, Instant.now().minusSeconds(1L).getEpochSecond()));

        HtxTrendPaperExecutionService.Result lateFill = service.report(filled("late-fill", "100", 4L));
        HtxTrendPaperExecutionService.Result expired = service.report(new HtxTrendPaperExecutionService.Report(
                "expired", RUN_ID, "BTC-USDT", "15min", BAR_TIME, "combo-v2", "EXPIRED", null, null, "ttl elapsed"));

        assertFalse(lateFill.accepted());
        assertEquals("price plan expired; report EXPIRED instead", lateFill.reason());
        assertTrue(expired.accepted());
    }

    private static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static HtxTrendPaperExecutionService.Report filled(String executionKey, String fillPrice, Long filledContracts) {
        return new HtxTrendPaperExecutionService.Report(executionKey, RUN_ID, "BTC-USDT", "15min", BAR_TIME,
                "combo-v2", "FILLED", new BigDecimal(fillPrice), filledContracts, "filled");
    }

    private static HtxTrendFactorSnapshotEntity snapshot(String schema, String factorsJson) {
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setMarketRegime("BROAD_UP");
        snapshot.setFactorsJson("{\"schema\":\"" + schema + "\"," + factorsJson.substring(1));
        return snapshot;
    }

    private static HtxTrendFactorSnapshotEntity pricePlan(String side, String maxAcceptablePrice, long plannedContracts,
                                                           long validUntilEpoch) {
        HtxTrendFactorSnapshotEntity snapshot = new HtxTrendFactorSnapshotEntity();
        snapshot.setMarketRegime("BROAD_UP");
        snapshot.setFactorsJson("{\"schema\":\"trend-price-plan-v1\",\"runId\":\"" + RUN_ID
                + "\",\"entry\":{\"side\":\"" + side + "\",\"maxAcceptablePrice\":"
                + maxAcceptablePrice + ",\"validUntilEpoch\":" + validUntilEpoch + ",\"contractSize\":0.1,"
                + "\"plannedContracts\":" + plannedContracts + "},\"exit\":{\"invalidationPrice\":94,"
                + "\"initialStopPrice\":95,\"structureTargetPrice\":110},\"risk\":{"
                + "\"estimatedRoundTripCostUsdt\":0.4}}");
        return snapshot;
    }
}
