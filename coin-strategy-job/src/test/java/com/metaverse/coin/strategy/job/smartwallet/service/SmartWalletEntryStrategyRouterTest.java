package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyCheckResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyRouteResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartWalletEntryStrategyRouterTest {

    private final SmartWalletEntryStrategyRouter router = new SmartWalletEntryStrategyRouter();

    @Test
    void shouldAcceptWhenAnyStrategyMatches() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS", "SINGLE_WALLET_OVERRIDE"));

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", false, "Consensus score is below threshold."),
                strategy("SINGLE_WALLET_OVERRIDE", true)
        ));

        assertTrue(result.isAccepted());
        assertEquals(List.of("SINGLE_WALLET_OVERRIDE"), result.getMatchedStrategies());
        assertTrue(result.getReasons().isEmpty());
    }

    @Test
    void shouldRejectWhenAllModeAndNotEveryStrategyMatches() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ALL");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS", "SINGLE_WALLET_OVERRIDE"));

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", true),
                strategy("SINGLE_WALLET_OVERRIDE", false, "Single-wallet override requirements are not met.")
        ));

        assertFalse(result.isAccepted());
        assertEquals(List.of("CONSENSUS"), result.getMatchedStrategies());
        assertEquals(List.of("Single-wallet override requirements are not met."), result.getReasons());
    }

    private SignalStrategyCheckResult strategy(String key, boolean accepted, String... reasons) {
        return SignalStrategyCheckResult.builder()
                .strategyKey(key)
                .accepted(accepted)
                .reasons(List.of(reasons))
                .build();
    }
}
