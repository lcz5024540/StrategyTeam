package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaverse.coin.strategy.common.dao.mapper.StrategyPromotionStateMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.StrategyPromotionStateEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyPromotionEvaluationRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.StrategyPromotionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StrategyPromotionService {

    private static final String STRATEGY_KEY = "POLYMARKET_COPY";
    private static final String SHADOW = "SHADOW";
    private static final String LIVE_1 = "LIVE_1_PERCENT";
    private static final String LIVE_5 = "LIVE_5_PERCENT";
    private static final String LIVE_20 = "LIVE_20_PERCENT";
    private static final String CHAMPION = "CHAMPION";
    private static final String ROLLED_BACK = "ROLLED_BACK";

    private final StrategyPromotionStateMapper stateMapper;
    private final ObjectMapper objectMapper;

    public StrategyPromotionView evaluate(StrategyPromotionEvaluationRequest request) {
        validate(request);
        StrategyPromotionStateEntity state = stateMapper.selectById(STRATEGY_KEY);
        if (state == null) {
            state = initialize(request);
            stateMapper.insert(state);
            return view(state, false, false, List.of());
        }
        if (ROLLED_BACK.equals(state.getStage())) {
            state = initialize(request);
            stateMapper.updateById(state);
            return view(state, false, false, List.of());
        }

        List<String> failures = hardRollbackFailures(request);
        if (!failures.isEmpty() && !SHADOW.equals(state.getStage())) {
            state.setStage(ROLLED_BACK)
                    .setRiskBudgetPct(BigDecimal.ZERO)
                    .setSettledTradeCount(request.getSettledTradeCount())
                    .setGateStatusJson(json(failures))
                    .setLastRollbackReason(String.join(";", failures))
                    .setUpdatedAt(new Date());
            stateMapper.updateById(state);
            return view(state, false, true, failures);
        }

        failures.addAll(stageGateFailures(state, request));
        boolean advanced = failures.isEmpty();
        if (advanced) {
            advance(state);
        }
        state.setSettledTradeCount(request.getSettledTradeCount())
                .setGateStatusJson(json(failures))
                .setUpdatedAt(new Date());
        stateMapper.updateById(state);
        return view(state, advanced, false, failures);
    }

    public StrategyPromotionView get() {
        StrategyPromotionStateEntity state = stateMapper.selectById(STRATEGY_KEY);
        return state == null ? null : view(state, false, ROLLED_BACK.equals(state.getStage()), List.of());
    }

    private StrategyPromotionStateEntity initialize(StrategyPromotionEvaluationRequest request) {
        Date now = new Date();
        return new StrategyPromotionStateEntity()
                .setStrategyKey(STRATEGY_KEY)
                .setChampionModelVersion(request.getChampionModelVersion())
                .setChallengerModelVersion(request.getChallengerModelVersion())
                .setStage(SHADOW)
                .setRiskBudgetPct(BigDecimal.ZERO)
                .setStageStartedAt(now)
                .setSettledTradeCount(0)
                .setGateStatusJson("[]")
                .setUpdatedAt(now);
    }

    private List<String> hardRollbackFailures(StrategyPromotionEvaluationRequest request) {
        List<String> failures = new ArrayList<>();
        if (Boolean.TRUE.equals(request.getDailyLossHaltTriggered())) failures.add("DAILY_LOSS_HALT");
        if (Boolean.TRUE.equals(request.getDrawdownHaltTriggered())) failures.add("DRAWDOWN_HALT");
        if (Boolean.TRUE.equals(request.getLabelBacklogDetected())) failures.add("LABEL_BACKLOG");
        if (Boolean.TRUE.equals(request.getCatastrophicSliceRegression())) failures.add("CATASTROPHIC_SLICE_REGRESSION");
        if (!Boolean.TRUE.equals(request.getDataQualityPassed())) failures.add("DATA_QUALITY_FAILED");
        if (safe(request.getAccountingDiscrepancyRate()).compareTo(new BigDecimal("0.01")) >= 0) {
            failures.add("ACCOUNTING_DISCREPANCY_AT_LEAST_1_PERCENT");
        }
        return failures;
    }

    private List<String> stageGateFailures(StrategyPromotionStateEntity state,
                                           StrategyPromotionEvaluationRequest request) {
        List<String> failures = hardRollbackFailures(request);
        int elapsedDays = (int) Duration.between(state.getStageStartedAt().toInstant(), new Date().toInstant()).toDays();
        int requiredDays;
        int requiredTrades;
        if (SHADOW.equals(state.getStage())) {
            requiredDays = 28;
            requiredTrades = 200;
            if (safe(request.getMaxDrawdownReductionRate()).compareTo(new BigDecimal("0.30")) < 0) failures.add("MAX_DRAWDOWN_REDUCTION_BELOW_30_PERCENT");
            if (safe(request.getRollingWinRateStdReductionRate()).compareTo(new BigDecimal("0.25")) < 0) failures.add("WIN_RATE_VOLATILITY_REDUCTION_BELOW_25_PERCENT");
            if (safe(request.getBootstrapNetEvLowerBoundUsdc()).compareTo(BigDecimal.ZERO) <= 0) failures.add("NET_EV_LOWER_BOUND_NOT_POSITIVE");
            if (safe(request.getProfitFactor()).compareTo(new BigDecimal("1.15")) < 0) failures.add("PROFIT_FACTOR_BELOW_1_15");
            if (safe(request.getWinRateDeltaPercentagePoints()).compareTo(new BigDecimal("3")) < 0) failures.add("WIN_RATE_DELTA_BELOW_3PP");
            if (safe(request.getWinRateImprovementProbability()).compareTo(new BigDecimal("0.80")) < 0) failures.add("WIN_RATE_IMPROVEMENT_PROBABILITY_BELOW_80_PERCENT");
            if (safe(request.getChallengerToChampionMaxDrawdownRatio()).compareTo(new BigDecimal("0.80")) > 0) failures.add("CHALLENGER_DRAWDOWN_ABOVE_80_PERCENT_OF_CHAMPION");
        } else if (LIVE_1.equals(state.getStage())) {
            requiredDays = 14;
            requiredTrades = 100;
        } else if (LIVE_5.equals(state.getStage())) {
            requiredDays = 14;
            requiredTrades = 150;
        } else if (LIVE_20.equals(state.getStage())) {
            requiredDays = 28;
            requiredTrades = 300;
        } else {
            return failures;
        }
        if (elapsedDays < requiredDays) failures.add("OBSERVATION_DAYS_" + elapsedDays + "_BELOW_" + requiredDays);
        if (request.getSettledTradeCount() < requiredTrades) failures.add("SETTLED_TRADES_" + request.getSettledTradeCount() + "_BELOW_" + requiredTrades);
        return failures;
    }

    private void advance(StrategyPromotionStateEntity state) {
        if (SHADOW.equals(state.getStage())) {
            state.setStage(LIVE_1).setRiskBudgetPct(new BigDecimal("0.01"));
        } else if (LIVE_1.equals(state.getStage())) {
            state.setStage(LIVE_5).setRiskBudgetPct(new BigDecimal("0.05"));
        } else if (LIVE_5.equals(state.getStage())) {
            state.setStage(LIVE_20).setRiskBudgetPct(new BigDecimal("0.20"));
        } else if (LIVE_20.equals(state.getStage())) {
            state.setStage(CHAMPION).setRiskBudgetPct(new BigDecimal("0.20"));
            state.setChampionModelVersion(state.getChallengerModelVersion()).setChallengerModelVersion(null);
        }
        state.setStageStartedAt(new Date()).setSettledTradeCount(0).setLastRollbackReason(null);
    }

    private StrategyPromotionView view(StrategyPromotionStateEntity state, boolean advanced,
                                       boolean rolledBack, List<String> failures) {
        return StrategyPromotionView.builder()
                .strategyKey(state.getStrategyKey())
                .championModelVersion(state.getChampionModelVersion())
                .challengerModelVersion(state.getChallengerModelVersion())
                .stage(state.getStage())
                .riskBudgetPct(state.getRiskBudgetPct())
                .stageStartedAt(state.getStageStartedAt())
                .settledTradeCount(state.getSettledTradeCount())
                .advanced(advanced)
                .rolledBack(rolledBack)
                .gateFailures(new ArrayList<>(failures))
                .lastRollbackReason(state.getLastRollbackReason())
                .build();
    }

    private void validate(StrategyPromotionEvaluationRequest request) {
        if (request == null || !StringUtils.hasText(request.getChampionModelVersion())
                || !StringUtils.hasText(request.getChallengerModelVersion())
                || request.getSettledTradeCount() == null || request.getSettledTradeCount() < 0) {
            throw new IllegalArgumentException("championModelVersion, challengerModelVersion and settledTradeCount are required");
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("promotion gate status is not serializable", e);
        }
    }
}
