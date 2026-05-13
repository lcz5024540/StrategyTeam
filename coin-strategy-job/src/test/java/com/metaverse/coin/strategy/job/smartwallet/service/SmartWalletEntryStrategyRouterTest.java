package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyCheckResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalStrategyRouteResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmartWalletEntryStrategyRouterTest {

    private final SmartWalletEntryStrategyRouter router = new SmartWalletEntryStrategyRouter();

    @Test
    void shouldAcceptWhenAnyStrategyMatches() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS", "HIGH_QUALITY_SINGLE_WALLET"));

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", false, "Consensus score is below threshold."),
                strategy("HIGH_QUALITY_SINGLE_WALLET", true)
        ), 1);

        assertTrue(result.isAccepted());
        assertEquals(List.of("HIGH_QUALITY_SINGLE_WALLET"), result.getMatchedStrategies());
        assertTrue(result.getReasons().isEmpty());
    }

    @Test
    void shouldRejectWhenAllModeAndNotEveryStrategyMatches() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ALL");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS", "HIGH_QUALITY_SINGLE_WALLET"));

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", true),
                strategy("HIGH_QUALITY_SINGLE_WALLET", false, "Single-wallet override requirements are not met.")
        ), 1);

        assertFalse(result.isAccepted());
        assertEquals(List.of("CONSENSUS"), result.getMatchedStrategies());
        assertEquals(List.of("Single-wallet override requirements are not met."), result.getReasons());
    }

    @Test
    void shouldReturnMultiplierOneWhenEntityCountIsOne() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS"));
        risk.setEnableConsensusStakeAmplifier(true);

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", true)
        ), 1);

        assertTrue(result.isAccepted());
        assertEquals(0, BigDecimal.ONE.compareTo(result.getStakeMultiplier()));
    }

    @Test
    void shouldReturnTier2MultiplierWhenEntityCountIsTwo() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS"));
        risk.setEnableConsensusStakeAmplifier(true);
        risk.setConsensusAmplifierTier2Multiplier(new BigDecimal("1.15"));
        risk.setConsensusAmplifierMaxMultiplier(new BigDecimal("1.50"));

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", true)
        ), 2);

        assertTrue(result.isAccepted());
        assertEquals(0, new BigDecimal("1.15").compareTo(result.getStakeMultiplier()));
    }

    @Test
    void shouldReturnTier3MultiplierWhenEntityCountIsThreeOrMore() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS"));
        risk.setEnableConsensusStakeAmplifier(true);
        risk.setConsensusAmplifierTier3PlusMultiplier(new BigDecimal("1.30"));
        risk.setConsensusAmplifierMaxMultiplier(new BigDecimal("1.50"));

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", true)
        ), 4);

        assertTrue(result.isAccepted());
        assertEquals(0, new BigDecimal("1.30").compareTo(result.getStakeMultiplier()));
    }

    @Test
    void shouldCapMultiplierAtMaxMultiplier() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS"));
        risk.setEnableConsensusStakeAmplifier(true);
        risk.setConsensusAmplifierTier3PlusMultiplier(new BigDecimal("2.00"));
        risk.setConsensusAmplifierMaxMultiplier(new BigDecimal("1.50"));

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", true)
        ), 5);

        assertTrue(result.isAccepted());
        assertEquals(0, new BigDecimal("1.50").compareTo(result.getStakeMultiplier()));
    }

    @Test
    void shouldReturnMultiplierOneWhenRejected() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS"));
        risk.setEnableConsensusStakeAmplifier(true);

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", false, "Consensus failed.")
        ), 3);

        assertFalse(result.isAccepted());
        assertEquals(0, BigDecimal.ONE.compareTo(result.getStakeMultiplier()));
    }

    @Test
    void shouldReturnMultiplierOneWhenAmplifierDisabled() {
        SmartWalletProperties.SignalRisk risk = new SmartWalletProperties.SignalRisk();
        risk.setEntryStrategyRouteMode("ANY");
        risk.setEnabledEntryStrategies(List.of("CONSENSUS"));
        risk.setEnableConsensusStakeAmplifier(false);

        SignalStrategyRouteResult result = router.route(risk, List.of(
                strategy("CONSENSUS", true)
        ), 3);

        assertTrue(result.isAccepted());
        assertEquals(0, BigDecimal.ONE.compareTo(result.getStakeMultiplier()));
    }

    private SignalStrategyCheckResult strategy(String key, boolean accepted, String... reasons) {
        return SignalStrategyCheckResult.builder()
                .strategyKey(key)
                .accepted(accepted)
                .reasons(List.of(reasons))
                .build();
    }
}
