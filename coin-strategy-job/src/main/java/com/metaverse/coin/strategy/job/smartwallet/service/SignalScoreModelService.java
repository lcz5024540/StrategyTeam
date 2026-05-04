package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.EntryTimingDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalScoreModelResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SignalScoreModelService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private final SmartWalletProperties properties;

    public SignalScoreModelResult score(SmartWalletProfileEntity profile,
                                        SmartWalletTradeEventEntity event,
                                        ConsensusSignalView consensus,
                                        TradeProfitabilityScoringService.ProfitabilityEstimate profitability,
                                        EntryTimingDecision entryTiming,
                                        BigDecimal recommendedStakeUsdc) {
        SmartWalletProperties.SignalRisk risk = properties.getSignalRisk();
        BigDecimal pProfit = clamp01(safe(profitability == null ? null : profitability.getCalibratedFollowProbability()));
        BigDecimal expectedReturnFactor = expectedReturnFactor(profitability, recommendedStakeUsdc, risk);
        BigDecimal liquidityFactor = liquidityFactor(event, entryTiming, recommendedStakeUsdc, risk);
        BigDecimal leaderReliabilityFactor = leaderReliabilityFactor(profile);
        BigDecimal riskPenalty = riskPenalty(profitability, entryTiming, consensus);

        BigDecimal score = ONE_HUNDRED
                .multiply(pProfit)
                .multiply(expectedReturnFactor)
                .multiply(liquidityFactor)
                .multiply(leaderReliabilityFactor)
                .multiply(riskPenalty)
                .max(BigDecimal.ZERO)
                .min(ONE_HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);

        List<String> reasons = new ArrayList<>();
        reasons.add("[SIGNAL_SCORE] score=" + score
                + ", pProfit=" + pct(pProfit)
                + ", expectedReturnFactor=" + expectedReturnFactor.setScale(4, RoundingMode.HALF_UP)
                + ", liquidityFactor=" + liquidityFactor.setScale(4, RoundingMode.HALF_UP)
                + ", leaderReliabilityFactor=" + leaderReliabilityFactor.setScale(4, RoundingMode.HALF_UP)
                + ", riskPenalty=" + riskPenalty.setScale(4, RoundingMode.HALF_UP) + ".");

        return SignalScoreModelResult.builder()
                .signalScore(score)
                .profitProbability(pProfit)
                .expectedReturnFactor(expectedReturnFactor)
                .liquidityFactor(liquidityFactor)
                .leaderReliabilityFactor(leaderReliabilityFactor)
                .riskPenalty(riskPenalty)
                .reasons(reasons)
                .build();
    }

    private BigDecimal expectedReturnFactor(TradeProfitabilityScoringService.ProfitabilityEstimate profitability,
                                            BigDecimal recommendedStakeUsdc,
                                            SmartWalletProperties.SignalRisk risk) {
        BigDecimal stake = safe(recommendedStakeUsdc);
        BigDecimal expectedNet = safe(profitability == null ? null : profitability.getEstimatedNetProfitabilityUsdc());
        if (stake.compareTo(BigDecimal.ZERO) <= 0 || expectedNet.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal targetReturn = safe(risk == null ? null : risk.getSignalScoreTargetReturnRate(), new BigDecimal("0.03"));
        BigDecimal targetPnl = stake.multiply(targetReturn).setScale(8, RoundingMode.HALF_UP);
        if (targetPnl.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return expectedNet.divide(targetPnl, 8, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(TWO);
    }

    private BigDecimal liquidityFactor(SmartWalletTradeEventEntity event,
                                       EntryTimingDecision entryTiming,
                                       BigDecimal recommendedStakeUsdc,
                                       SmartWalletProperties.SignalRisk risk) {
        BigDecimal base = BigDecimal.ONE;
        BigDecimal stake = safe(recommendedStakeUsdc);
        BigDecimal triggerSize = safe(event == null ? null : event.getSizeUsdc());
        if (stake.compareTo(BigDecimal.ZERO) > 0 && triggerSize.compareTo(BigDecimal.ZERO) > 0) {
            base = triggerSize.divide(stake.max(BigDecimal.ONE), 8, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        }
        BigDecimal spreadPct = safe(entryTiming == null ? null : entryTiming.getSpreadPct());
        BigDecimal maxSpread = safe(risk == null ? null : risk.getMaxEntrySpreadPct(), new BigDecimal("0.04"));
        BigDecimal spreadFactor = maxSpread.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ONE
                : BigDecimal.ONE.subtract(spreadPct.divide(maxSpread, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("0.50")))
                .max(safe(risk == null ? null : risk.getSignalScoreMinLiquidityFactor(), new BigDecimal("0.10")))
                .min(BigDecimal.ONE);
        return base.multiply(spreadFactor).max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal leaderReliabilityFactor(SmartWalletProfileEntity profile) {
        if (profile == null) {
            return new BigDecimal("0.50");
        }
        BigDecimal walletScore = safe(profile.getWalletScore());
        BigDecimal recentWinRate = safe(profile.getRecentWinRate30d(), safe(profile.getWinRate()));
        BigDecimal drawdownPenalty = safe(profile.getMaxDrawdownRate()).multiply(new BigDecimal("0.50")).min(new BigDecimal("0.50"));
        BigDecimal tradeCountScore = normalizedTradeCount(profile.getRecentTradeCount30d());
        return walletScore.multiply(new BigDecimal("0.45"))
                .add(recentWinRate.multiply(new BigDecimal("0.25")))
                .add(tradeCountScore.multiply(new BigDecimal("0.20")))
                .add(new BigDecimal("0.10"))
                .subtract(drawdownPenalty)
                .max(new BigDecimal("0.10"))
                .min(BigDecimal.ONE)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal riskPenalty(TradeProfitabilityScoringService.ProfitabilityEstimate profitability,
                                   EntryTimingDecision entryTiming,
                                   ConsensusSignalView consensus) {
        BigDecimal slippagePenalty = safe(profitability == null ? null : profitability.getSlippageSpikeProbability())
                .multiply(new BigDecimal("0.35"));
        BigDecimal driftPenalty = safe(entryTiming == null ? null : entryTiming.getDriftRate()).max(BigDecimal.ZERO)
                .multiply(new BigDecimal("4.00"));
        BigDecimal dominancePenalty = safe(consensus == null ? null : consensus.getTopEntityWeightShare())
                .subtract(new BigDecimal("0.75"))
                .max(BigDecimal.ZERO)
                .multiply(new BigDecimal("0.50"));
        return BigDecimal.ONE
                .subtract(slippagePenalty)
                .subtract(driftPenalty)
                .subtract(dominancePenalty)
                .max(new BigDecimal("0.05"))
                .min(BigDecimal.ONE)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizedTradeCount(Integer count) {
        int safeCount = count == null ? 0 : Math.max(0, count);
        return BigDecimal.valueOf(Math.min(safeCount, 30))
                .divide(new BigDecimal("30"), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal clamp01(BigDecimal value) {
        return safe(value).max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal safe(BigDecimal value, BigDecimal fallback) {
        return value == null ? safe(fallback) : value;
    }

    private String pct(BigDecimal value) {
        return safe(value).multiply(ONE_HUNDRED).setScale(2, RoundingMode.HALF_UP) + "%";
    }
}
