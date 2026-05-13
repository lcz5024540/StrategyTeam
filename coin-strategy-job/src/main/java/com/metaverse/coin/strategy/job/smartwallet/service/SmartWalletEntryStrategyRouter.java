package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyCheckResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyRouteResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class SmartWalletEntryStrategyRouter {

    private static final String DEFAULT_STRATEGY = "CONSENSUS";
    private static final String MODE_ALL = "ALL";

    public SignalStrategyRouteResult route(SmartWalletProperties.SignalRisk risk,
                                           List<SignalStrategyCheckResult> availableChecks,
                                           int dedupedEntityCount) {
        List<String> enabledStrategies = resolveEnabledStrategies(risk);
        List<SignalStrategyCheckResult> activeChecks = availableChecks == null
                ? List.of()
                : availableChecks.stream()
                .filter(check -> check != null && enabledStrategies.contains(normalize(check.getStrategyKey())))
                .toList();

        String routeMode = normalizeRouteMode(risk);
        List<String> matchedStrategies = activeChecks.stream()
                .filter(SignalStrategyCheckResult::isAccepted)
                .map(SignalStrategyCheckResult::getStrategyKey)
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .toList();

        boolean accepted = MODE_ALL.equals(routeMode)
                ? !activeChecks.isEmpty() && matchedStrategies.size() == activeChecks.size()
                : !matchedStrategies.isEmpty();

        List<String> reasons = new ArrayList<>();
        if (!accepted) {
            for (SignalStrategyCheckResult check : activeChecks) {
                if (check.isAccepted() || check.getReasons() == null) {
                    continue;
                }
                reasons.addAll(check.getReasons());
            }
            if (reasons.isEmpty()) {
                reasons.add("No entry strategy matched the current signal.");
            }
        }

        BigDecimal stakeMultiplier = computeStakeMultiplier(risk, dedupedEntityCount, accepted);

        return SignalStrategyRouteResult.builder()
                .accepted(accepted)
                .routeMode(routeMode)
                .enabledStrategies(enabledStrategies)
                .matchedStrategies(new ArrayList<>(matchedStrategies))
                .reasons(reasons)
                .stakeMultiplier(stakeMultiplier)
                .build();
    }

    private BigDecimal computeStakeMultiplier(SmartWalletProperties.SignalRisk risk,
                                              int dedupedEntityCount,
                                              boolean accepted) {
        if (!accepted || risk == null || !risk.isEnableConsensusStakeAmplifier()) {
            return BigDecimal.ONE;
        }
        if (dedupedEntityCount < 2) {
            return BigDecimal.ONE;
        }
        BigDecimal raw = dedupedEntityCount == 2
                ? safe(risk.getConsensusAmplifierTier2Multiplier())
                : safe(risk.getConsensusAmplifierTier3PlusMultiplier());
        BigDecimal cap = safe(risk.getConsensusAmplifierMaxMultiplier());
        if (cap.compareTo(BigDecimal.ONE) < 0) {
            cap = BigDecimal.ONE;
        }
        return raw.min(cap);
    }

    private List<String> resolveEnabledStrategies(SmartWalletProperties.SignalRisk risk) {
        List<String> configured = risk == null ? null : risk.getEnabledEntryStrategies();
        if (configured == null || configured.isEmpty()) {
            return List.of(DEFAULT_STRATEGY);
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : configured) {
            if (StringUtils.hasText(item)) {
                normalized.add(normalize(item));
            }
        }
        return normalized.isEmpty() ? List.of(DEFAULT_STRATEGY) : new ArrayList<>(normalized);
    }

    private String normalizeRouteMode(SmartWalletProperties.SignalRisk risk) {
        String configured = risk == null ? null : risk.getEntryStrategyRouteMode();
        return MODE_ALL.equals(normalize(configured)) ? MODE_ALL : "ANY";
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ONE : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
