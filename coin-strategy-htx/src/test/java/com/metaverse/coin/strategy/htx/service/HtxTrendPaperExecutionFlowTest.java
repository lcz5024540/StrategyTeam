package com.metaverse.coin.strategy.htx.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapKline;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCandidateSignalRecorder;
import com.metaverse.coin.strategy.htx.persistence.HtxTrendCombinationRecorder;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendFactorSnapshotEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendPaperExecutionEntity;
import com.metaverse.coin.strategy.htx.persistence.entity.HtxTrendStrategyPolicyEntity;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendFactorSnapshotMapper;
import com.metaverse.coin.strategy.htx.persistence.mapper.HtxTrendPaperExecutionMapper;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Direction;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.Regime;
import com.metaverse.coin.strategy.htx.service.HtxTrendEnsembleSignalCalculator.TrendCandidateSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Contract test for SIG-01 -> PLAN-01 -> EXT-01 -> EXT-02; it uses no HTX exchange client. */
class HtxTrendPaperExecutionFlowTest {
    private static final String CONTRACT = "BTC-USDT";
    private static final String PERIOD = "15min";
    private static final long BAR_TIME = 1_790_069_400L;
    private static final String POLICY = "combo-v2";
    private static final String RUN_ID = "trend-entry:BTC-USDT:15min:1790069400";
    private static final String EXECUTION_KEY = "paper:btc:1790069400:partial-1";

    @Test
    void preservesOneRunIdFromFrozenPlanThroughOutboxFillAndPaperExitPlan() {
        InMemorySnapshots snapshots = new InMemorySnapshots();
        HtxTrendCombinationService combinationService = combinationService(snapshots);
        HtxTrendPaperExecutionMapper executionMapper = mock(HtxTrendPaperExecutionMapper.class);
        Map<String, HtxTrendPaperExecutionEntity> executions = new LinkedHashMap<>();
        when(executionMapper.findByExecutionKey(anyString()))
                .thenAnswer(invocation -> executions.get(invocation.getArgument(0)));
        when(executionMapper.insert(any())).thenAnswer(invocation -> {
            HtxTrendPaperExecutionEntity entity = invocation.getArgument(0);
            entity.setId(101L);
            executions.put(entity.getExecutionKey(), entity);
            return 1;
        });

        HtxTrendPaperExecutionService paperExecutionService = new HtxTrendPaperExecutionService(
                combinationService, executionMapper);
        HtxTrendCombinationService.Selection selection = combinationService.select(CONTRACT, PERIOD,
                breakoutCandles(), trendSignal());
        assertTrue(selection.accepted());
        assertEquals(RUN_ID, selection.runId());
        combinationService.recordPricePlan(CONTRACT, PERIOD, BAR_TIME, POLICY, "BROAD_UP", pricePlan(selection.runId()));

        assertTrue(snapshots.byKey.containsKey(candidateEvidenceKey()));
        HtxTrendFactorSnapshotEntity frozenPlan = snapshots.byKey.get(pricePlanKey());
        HtxTrendFactorSnapshotEntity entryReady = snapshots.byKey.get(outboxKey());
        assertNotNull(frozenPlan);
        assertNotNull(entryReady);
        assertEquals(RUN_ID, JSON.parseObject(frozenPlan.getFactorsJson()).getString("runId"));
        assertEquals("ENTRY_READY", JSON.parseObject(entryReady.getFactorsJson()).getString("eventType"));
        assertEquals(RUN_ID, JSON.parseObject(entryReady.getFactorsJson()).getString("runId"));
        assertEquals("NO_EXCHANGE_ORDER", JSON.parseObject(entryReady.getFactorsJson()).getString("executionBoundary"));

        HtxTrendPaperExecutionService.Result result = paperExecutionService.report(
                new HtxTrendPaperExecutionService.Report(EXECUTION_KEY, RUN_ID, CONTRACT, PERIOD, BAR_TIME, POLICY,
                        "PARTIALLY_FILLED", new BigDecimal("99"), 2L, "simulated passive partial fill"));

        assertTrue(result.accepted());
        assertEquals(101L, result.id());
        assertEquals(6, snapshots.byKey.size());
        assertTrue(snapshots.byKey.containsKey(candidateEvidenceKey()));
        assertTrue(snapshots.byKey.containsKey(CONTRACT + ":" + PERIOD + ":" + BAR_TIME + ":model-coverage-v1"));
        assertTrue(snapshots.byKey.containsKey(CONTRACT + ":" + PERIOD + ":" + BAR_TIME + ":model-analysis-v2"));
        assertTrue(snapshots.byKey.containsKey(pricePlanKey()));
        assertTrue(snapshots.byKey.containsKey(outboxKey()));

        HtxTrendFactorSnapshotEntity paperExit = snapshots.byKey.values().stream()
                .filter(snapshot -> snapshot.getSnapshotKey().startsWith("PAPER_EXIT:"))
                .findFirst().orElseThrow();
        JSONObject exitJson = JSON.parseObject(paperExit.getFactorsJson());
        assertEquals("paper-exit-plan-v1", exitJson.getString("schema"));
        assertEquals(RUN_ID, exitJson.getJSONObject("source").getString("runId"));
        assertEquals(EXECUTION_KEY, exitJson.getJSONObject("source").getString("executionKey"));
        assertEquals("trend-price-plan-v1", exitJson.getJSONObject("source").getString("pricePlanRef"));
        assertEquals("LONG", exitJson.getJSONObject("entry").getString("side"));
        assertDecimalEquals("99", exitJson.getJSONObject("entry").getBigDecimal("fillPrice"));
        assertEquals(2L, exitJson.getJSONObject("entry").getLongValue("filledContracts"));
        assertDecimalEquals("4", exitJson.getJSONObject("risk").getBigDecimal("distance"));
        assertDecimalEquals("0.8", exitJson.getJSONObject("risk").getBigDecimal("plannedRiskUsdt"));
        assertDecimalEquals("103", exitJson.getJSONObject("exit").getBigDecimal("target1RPrice"));
        assertDecimalEquals("107", exitJson.getJSONObject("exit").getBigDecimal("target2RPrice"));
        assertDecimalEquals("2.2", exitJson.getJSONObject("risk").getBigDecimal("structureGrossPnlUsdt"));
        assertDecimalEquals("0.2", exitJson.getJSONObject("risk").getBigDecimal("estimatedRoundTripCostUsdt"));
        assertDecimalEquals("2.5", exitJson.getJSONObject("risk").getBigDecimal("netRAtStructureTarget"));

        HtxTrendPaperExecutionEntity execution = executions.get(EXECUTION_KEY);
        assertNotNull(execution);
        assertEquals(RUN_ID, execution.getRunId());
        assertEquals(CONTRACT, execution.getContractCode());
        assertEquals("PARTIALLY_FILLED", execution.getOutcome());
        assertDecimalEquals("99", execution.getFillPrice());
        assertEquals(2L, execution.getFilledContracts());
        assertTrue(Arrays.stream(HtxTrendPaperExecutionService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().contains("HtxUsdtSwap")));
    }

    private static HtxTrendCombinationService combinationService(InMemorySnapshots snapshots) {
        HtxTrendStrategyPolicyService policyService = mock(HtxTrendStrategyPolicyService.class);
        when(policyService.enabledPolicies()).thenReturn(List.of(policy()));
        HtxTrendFactorSnapshotMapper factorSnapshotMapper = mock(HtxTrendFactorSnapshotMapper.class);
        when(factorSnapshotMapper.findBySnapshotKey(anyString()))
                .thenAnswer(invocation -> snapshots.byKey.get(invocation.getArgument(0)));
        when(factorSnapshotMapper.findLatestBySnapshotPrefix(anyString()))
                .thenAnswer(invocation -> snapshots.latestByPrefix(invocation.getArgument(0)));
        when(factorSnapshotMapper.insert(any())).thenAnswer(invocation -> {
            HtxTrendFactorSnapshotEntity snapshot = invocation.getArgument(0);
            snapshot.setId(snapshots.nextId.getAndIncrement());
            snapshots.byKey.put(snapshot.getSnapshotKey(), snapshot);
            return 1;
        });
        return new HtxTrendCombinationService(policyService,
                new HtxTrendCombinationRecorder(factorSnapshotMapper), mock(HtxTrendCandidateSignalRecorder.class));
    }

    private static HtxTrendCombinationService.PricePlanEvidence pricePlan(String runId) {
        return new HtxTrendCombinationService.PricePlanEvidence(runId, "INTRADAY_15M",
                new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("100"),
                Instant.now().plusSeconds(300L).getEpochSecond(), new BigDecimal("94"), new BigDecimal("95"),
                new BigDecimal("104"), new BigDecimal("108"), new BigDecimal("110"), new BigDecimal("1.6"),
                new BigDecimal("2.75"), "LONG", new BigDecimal("0.1"), 4L, new BigDecimal("0.4"));
    }

    private static HtxTrendStrategyPolicyEntity policy() {
        HtxTrendStrategyPolicyEntity policy = new HtxTrendStrategyPolicyEntity();
        policy.setPolicyCode("DONCHIAN_BOX");
        policy.setPolicyVersion(POLICY);
        policy.setPrimaryModel("DONCHIAN_BREAKOUT");
        policy.setConfirmationModels("BOX_BREAKOUT");
        policy.setBaseNotionalUsdt(new BigDecimal("100"));
        policy.setMaxNotionalUsdt(new BigDecimal("100"));
        policy.setScoreThreshold(new BigDecimal("80"));
        policy.setBoxWindowBars(32);
        return policy;
    }

    private static TrendCandidateSignal trendSignal() {
        return new TrendCandidateSignal(PERIOD, BAR_TIME, Direction.LONG, Regime.TRENDING_UP,
                new BigDecimal("90"), new BigDecimal("90"), BigDecimal.ZERO, new BigDecimal("90"),
                new BigDecimal("120"), new BigDecimal("119"), new BigDecimal("118"), new BigDecimal("117"),
                new BigDecimal("119"), new BigDecimal("95"), new BigDecimal("2"), new BigDecimal("0.02"),
                new BigDecimal("30"), new BigDecimal("0.02"), new BigDecimal("1.5"), "valid", "none");
    }

    private static List<HtxSwapKline> breakoutCandles() {
        List<HtxSwapKline> candles = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            candles.add(candle(index, "100", "110", "101", "105"));
        }
        candles.add(candle(32L, "106", "121", "107", "120"));
        return candles;
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

    private static String candidateEvidenceKey() {
        return CONTRACT + ":" + PERIOD + ":" + BAR_TIME + ":" + POLICY + ":DONCHIAN_BOX:evidence-v3";
    }

    private static String pricePlanKey() {
        return CONTRACT + ":" + PERIOD + ":" + BAR_TIME + ":" + POLICY + ":price-plan-v1:" + RUN_ID;
    }

    private static String outboxKey() {
        return CONTRACT + ":" + PERIOD + ":" + BAR_TIME + ":" + POLICY + ":signal-outbox-v1:ENTRY_READY:" + RUN_ID;
    }

    private static void assertDecimalEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private static final class InMemorySnapshots {
        private final AtomicLong nextId = new AtomicLong(1L);
        private final Map<String, HtxTrendFactorSnapshotEntity> byKey = new LinkedHashMap<>();

        private HtxTrendFactorSnapshotEntity latestByPrefix(String prefix) {
            return byKey.values().stream()
                    .filter(snapshot -> snapshot.getSnapshotKey().startsWith(prefix))
                    .max(Comparator.comparing(HtxTrendFactorSnapshotEntity::getId))
                    .orElse(null);
        }
    }
}
