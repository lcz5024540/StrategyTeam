package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SmartWalletSignalRiskService {

    private final SmartWalletProperties properties;
    private final ExecutionWalletContextLoader executionWalletContextLoader;
    private final PolymarketConsensusSignalService consensusSignalService;

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile, SmartWalletTradeEventEntity event) {
        return evaluate(profile, event, null);
    }

    public SignalRiskDecision evaluate(SmartWalletProfileEntity profile,
                                       SmartWalletTradeEventEntity event,
                                       SmartWalletProperties.SignalRisk overrideRisk) {
        List<String> reasons = new ArrayList<>();
        boolean accepted = true;
        boolean overrideApplied = false;

        if (profile == null || !Boolean.TRUE.equals(profile.getQualified()) || !Boolean.TRUE.equals(profile.getWatchEnabled())) {
            accepted = false;
            reasons.add("Wallet is not qualified in watchlist.");
        }

        SmartWalletProperties.SignalRisk risk = overrideRisk == null ? properties.getSignalRisk() : overrideRisk;
        if (!risk.isAllowSellSignal() && "SELL".equalsIgnoreCase(event.getSide())) {
            accepted = false;
            reasons.add("SELL signals are disabled by risk policy.");
        }

        BigDecimal triggerSize = safe(event.getSizeUsdc());
        if (triggerSize.compareTo(safe(risk.getMinTriggerSizeUsdc())) < 0) {
            accepted = false;
            reasons.add("Trigger size is below minimum threshold.");
        }

        BigDecimal price = safe(event.getPrice());
        if (price.compareTo(safe(risk.getMinFollowPrice())) < 0 || price.compareTo(safe(risk.getMaxFollowPrice())) > 0) {
            accepted = false;
            reasons.add("Trigger price is outside follow range.");
        }

        if (event.getOpenedAt() != null) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - event.getOpenedAt().getTime());
            if (ageMs > risk.getMaxSignalAgeSeconds() * 1000L) {
                accepted = false;
                reasons.add("Signal is stale.");
            }
        }

        ConsensusSignalView consensus = consensusSignalService.buildConsensusForEvent(event);
        boolean consensusWalletCountFailed = false;
        boolean consensusScoreFailed = false;
        if (risk.isEnableConsensusGate()) {
            if (consensus.getDistinctWallets() < risk.getMinDistinctWallets()) {
                consensusWalletCountFailed = true;
            }
            if (safe(consensus.getConsensusScore()).compareTo(safe(risk.getMinConsensusScore())) < 0) {
                consensusScoreFailed = true;
            }
        }

        BigDecimal walletScore = profile == null ? BigDecimal.ZERO : safe(profile.getWalletScore());
        BigDecimal recommendedStake = triggerSize
                .multiply(walletScore)
                .multiply(properties.getBankrollUtilizationRate())
                .min(properties.getMaxStakeUsdc())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal minOrderUsdc = executionWalletContextLoader.load().getMinOrderUsdc();
        if (recommendedStake.compareTo(minOrderUsdc) < 0) {
            accepted = false;
            reasons.add("Recommended stake is below execution wallet minimum order.");
        }

        boolean singleWalletOverride = qualifiesSingleWalletOverride(profile, risk, triggerSize, recommendedStake);
        if (singleWalletOverride && consensusWalletCountFailed) {
            overrideApplied = true;
            consensusWalletCountFailed = false;
            reasons.add("Single-wallet override bypassed consensus wallet-count gate.");
        }

        if (consensusWalletCountFailed) {
            accepted = false;
            reasons.add("Consensus wallet count is below threshold.");
        }
        if (consensusScoreFailed) {
            accepted = false;
            reasons.add("Consensus score is below threshold.");
        }

        if (accepted) {
            reasons.add("Signal passed watchlist, freshness, size and price checks.");
        }

        return SignalRiskDecision.builder()
                .accepted(accepted)
                .overrideApplied(overrideApplied)
                .recommendedStakeUsdc(recommendedStake)
                .consensusWallets(consensus.getDistinctWallets())
                .consensusScore(consensus.getConsensusScore())
                .reasons(reasons)
                .build();
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean qualifiesSingleWalletOverride(SmartWalletProfileEntity profile,
                                                  SmartWalletProperties.SignalRisk risk,
                                                  BigDecimal triggerSize,
                                                  BigDecimal recommendedStake) {
        if (!risk.isEnableSingleWalletOverride() || profile == null) {
            return false;
        }
        return safe(profile.getWalletScore()).compareTo(safe(risk.getSingleWalletOverrideMinWalletScore())) >= 0
                && safe(profile.getWinRate()).compareTo(safe(risk.getSingleWalletOverrideMinWinRate())) >= 0
                && (profile.getTotalTrades() == null ? 0 : profile.getTotalTrades()) >= (risk.getSingleWalletOverrideMinTrades() == null ? 0 : risk.getSingleWalletOverrideMinTrades())
                && triggerSize.compareTo(safe(risk.getSingleWalletOverrideMinTriggerSizeUsdc())) >= 0
                && recommendedStake.compareTo(safe(risk.getSingleWalletOverrideMinRecommendedStakeUsdc())) >= 0;
    }
}
