package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyPromotionStateMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyPromotionStateEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyPromotionEvaluationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyPromotionView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StrategyPromotionServiceTest {

    @Mock
    private StrategyPromotionStateMapper stateMapper;

    @Test
    void firstEvaluationOnlyStartsShadowObservation() {
        StrategyPromotionService service = new StrategyPromotionService(stateMapper, new ObjectMapper());
        when(stateMapper.selectById("POLYMARKET_COPY")).thenReturn(null);

        StrategyPromotionView view = service.evaluate(passingRequest(1000));

        assertEquals("SHADOW", view.getStage());
        assertEquals(BigDecimal.ZERO, view.getRiskBudgetPct());
        assertFalse(view.getAdvanced());
        verify(stateMapper).insert(any());
    }

    @Test
    void shadowAdvancesOnlyAfterTimeSamplesStabilityAndGainGatesPass() {
        StrategyPromotionService service = new StrategyPromotionService(stateMapper, new ObjectMapper());
        StrategyPromotionStateEntity state = state("SHADOW", 29, BigDecimal.ZERO);
        when(stateMapper.selectById("POLYMARKET_COPY")).thenReturn(state);

        StrategyPromotionView view = service.evaluate(passingRequest(200));

        assertTrue(view.getAdvanced());
        assertEquals("LIVE_1_PERCENT", view.getStage());
        assertEquals(new BigDecimal("0.01"), view.getRiskBudgetPct());
    }

    @Test
    void liveStageRollsBackImmediatelyOnAccountingDiscrepancy() {
        StrategyPromotionService service = new StrategyPromotionService(stateMapper, new ObjectMapper());
        StrategyPromotionStateEntity state = state("LIVE_5_PERCENT", 20, new BigDecimal("0.05"));
        when(stateMapper.selectById("POLYMARKET_COPY")).thenReturn(state);
        StrategyPromotionEvaluationRequest request = passingRequest(200);
        request.setAccountingDiscrepancyRate(new BigDecimal("0.0100"));

        StrategyPromotionView view = service.evaluate(request);

        assertTrue(view.getRolledBack());
        assertEquals("ROLLED_BACK", view.getStage());
        assertEquals(BigDecimal.ZERO, view.getRiskBudgetPct());
        assertTrue(view.getGateFailures().contains("ACCOUNTING_DISCREPANCY_AT_LEAST_1_PERCENT"));
    }

    private StrategyPromotionStateEntity state(String stage, int ageDays, BigDecimal budget) {
        return new StrategyPromotionStateEntity()
                .setStrategyKey("POLYMARKET_COPY")
                .setChampionModelVersion("champion-v1")
                .setChallengerModelVersion("challenger-v2")
                .setStage(stage)
                .setRiskBudgetPct(budget)
                .setStageStartedAt(Date.from(Instant.now().minus(ageDays, ChronoUnit.DAYS)))
                .setSettledTradeCount(0)
                .setGateStatusJson("[]");
    }

    private StrategyPromotionEvaluationRequest passingRequest(int settledTrades) {
        StrategyPromotionEvaluationRequest request = new StrategyPromotionEvaluationRequest();
        request.setChampionModelVersion("champion-v1");
        request.setChallengerModelVersion("challenger-v2");
        request.setSettledTradeCount(settledTrades);
        request.setMaxDrawdownReductionRate(new BigDecimal("0.30"));
        request.setRollingWinRateStdReductionRate(new BigDecimal("0.25"));
        request.setCatastrophicSliceRegression(false);
        request.setDataQualityPassed(true);
        request.setBootstrapNetEvLowerBoundUsdc(new BigDecimal("0.01"));
        request.setProfitFactor(new BigDecimal("1.15"));
        request.setWinRateDeltaPercentagePoints(new BigDecimal("3"));
        request.setWinRateImprovementProbability(new BigDecimal("0.80"));
        request.setChallengerToChampionMaxDrawdownRatio(new BigDecimal("0.80"));
        request.setAccountingDiscrepancyRate(BigDecimal.ZERO);
        request.setLabelBacklogDetected(false);
        request.setDailyLossHaltTriggered(false);
        request.setDrawdownHaltTriggered(false);
        return request;
    }
}
