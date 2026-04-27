package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletSignalRiskServiceTest {

    @Mock
    private ExecutionWalletContextLoader executionWalletContextLoader;
    @Mock
    private PolymarketConsensusSignalService consensusSignalService;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private SmartWalletMarketStateService marketStateService;
    @Mock
    private SmartWalletAccountingService accountingService;
    @Mock
    private TradeProfitabilityScoringService tradeProfitabilityScoringService;
    @Mock
    private MarketConcentrationService concentrationService;
    @Mock
    private SmartWalletLeaderboardStateService leaderboardStateService;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private KellyStakeCalculator kellyStakeCalculator;
    @Mock
    private PolymarketApiClient polymarketApiClient;
    @Mock
    private WalletBehaviorProfileService walletBehaviorProfileService;
    @Mock
    private WalletArchetypeClassifierService walletArchetypeClassifierService;
    @Mock
    private SmartWalletManualAllowlistService manualAllowlistService;

    private SmartWalletProperties properties;
    private SmartWalletSignalRiskService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        properties.getSignalRisk().setEntryStrategyRouteMode("ANY");
        properties.getSignalRisk().setEnabledEntryStrategies(java.util.List.of("CONSENSUS", "HIGH_QUALITY_SINGLE_WALLET"));
        service = new SmartWalletSignalRiskService(
                properties,
                executionWalletContextLoader,
                consensusSignalService,
                positionMapper,
                followTaskMapper,
                marketMetadataService,
                marketStateService,
                accountingService,
                tradeProfitabilityScoringService,
                concentrationService,
                leaderboardStateService,
                new SmartWalletEntryStrategyRouter(),
                marketInfoService,
                kellyStakeCalculator,
                polymarketApiClient,
                walletArchetypeClassifierService,
                walletBehaviorProfileService,
                manualAllowlistService
        );
    }

    @Test
    void shouldAllowOpenWhenHighQualitySingleWalletMatches() {
        properties.getSignalRisk().setMinDistinctEntities(2);
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.80"))
                .setTotalTrades(120)
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.75"));
        SmartWalletTradeEventEntity event = baseEvent()
                .setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.10"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile(any())).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.80"))
                .qualityScore(new BigDecimal("0.78"))
                .earlyExitRate(new BigDecimal("0.20"))
                .settlementCompletionRate(new BigDecimal("0.80"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted(),
                "High-quality single wallet should be accepted regardless of consensusScore. Reasons: "
                        + decision.getReasons());
        assertTrue(decision.isOverrideApplied());
        assertTrue(decision.getMatchedStrategies().contains("HIGH_QUALITY_SINGLE_WALLET"));
    }

    @Test
    void shouldAllowHotRetentionWalletThroughWatchlistAdmission() {
        SmartWalletProfileEntity profile = baseProfile()
                .setQualified(Boolean.FALSE)
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier("HOT")
                .setWalletScore(new BigDecimal("0.64"))
                .setWinRate(BigDecimal.ONE)
                .setTotalTrades(50)
                .setRecentTradeCount7d(3)
                .setRecentWinRate7d(BigDecimal.ONE);
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.25"))
                .build();

        prepareCommonMocks(consensus);

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted());
        assertTrue(decision.getMatchedStrategies().contains("CONSENSUS"));
        assertFalse(decision.getReasons().stream().anyMatch(item -> item.contains("Wallet is not qualified in watchlist")));
    }

    @Test
    void shouldRejectWarmWalletEvenWhenQualifiedAndWatchEnabled() {
        SmartWalletProfileEntity profile = baseProfile()
                .setMonitorTier("WARM")
                .setWalletScore(new BigDecimal("0.85"))
                .setWinRate(new BigDecimal("0.75"))
                .setTotalTrades(80)
                .setRecentTradeCount7d(5)
                .setRecentWinRate7d(new BigDecimal("0.70"));
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.80"))
                .build();

        prepareCommonMocks(consensus);

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("Wallet is not qualified in watchlist")));
    }

    @Test
    void shouldRejectColdWalletEvenWhenQualifiedAndWatchEnabled() {
        SmartWalletProfileEntity profile = baseProfile()
                .setMonitorTier("COLD")
                .setWalletScore(new BigDecimal("0.85"))
                .setWinRate(new BigDecimal("0.75"))
                .setTotalTrades(80)
                .setRecentTradeCount7d(5)
                .setRecentWinRate7d(new BigDecimal("0.70"));
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.80"))
                .build();

        prepareCommonMocks(consensus);

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("Wallet is not qualified in watchlist")));
    }

    @Test
    void shouldRejectManuallyDisabledHotWallet() {
        SmartWalletProfileEntity profile = baseProfile()
                .setManualWatchEnabled(Boolean.FALSE)
                .setWalletScore(new BigDecimal("0.85"))
                .setWinRate(new BigDecimal("0.75"))
                .setTotalTrades(80)
                .setRecentTradeCount7d(5)
                .setRecentWinRate7d(new BigDecimal("0.70"));
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.80"))
                .build();

        prepareCommonMocks(consensus);

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[MANUAL_WATCH_DISABLED]")));
    }

    @Test
    void shouldRejectWhenNoEntryStrategyMatches() {
        properties.getSignalRisk().setMinDistinctEntities(2);
        properties.getSignalRisk().setMinConsensusScore(new BigDecimal("0.65"));
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.70"))
                .setWinRate(new BigDecimal("0.60"))
                .setTotalTrades(20)
                .setRecentTradeCount7d(2)
                .setRecentWinRate7d(new BigDecimal("0.50"));
        SmartWalletTradeEventEntity event = baseEvent()
                .setSizeUsdc(new BigDecimal("20"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.40"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile(any())).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.50"))
                .qualityScore(new BigDecimal("0.50"))
                .earlyExitRate(new BigDecimal("0.30"))
                .settlementCompletionRate(new BigDecimal("0.60"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getMatchedStrategies().isEmpty());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("Consensus wallet count")));
    }

    @Test
    void shouldDownsizeStakeWhenLiveWalletBalanceIsBelowConfiguredBudget() {
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setKellyStakeEnabled(false);

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80);
        SmartWalletTradeEventEntity event = baseEvent()
                .setSizeUsdc(new BigDecimal("100"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.80"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile(any())).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.70"))
                .earlyExitRate(new BigDecimal("0.20"))
                .settlementCompletionRate(new BigDecimal("0.80"))
                .build());
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("500"));
        when(polymarketApiClient.fetchBalance("0xlive")).thenReturn(new BigDecimal("100"));
        when(executionWalletContextLoader.load()).thenReturn(ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .enabled(true)
                .address("0xlive")
                .minOrderUsdc(new BigDecimal("0.10"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.48"), decision.getRecommendedStakeUsdc());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("Live wallet balance sizing applied")));
    }

    @Test
    void shouldRejectFastExitOrSkipWalletAtEntry() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xfast")
                .setWalletScore(new BigDecimal("0.90"))
                .setWinRate(new BigDecimal("0.75"))
                .setTotalTrades(90)
                .setRecentTradeCount7d(7)
                .setRecentWinRate7d(new BigDecimal("0.72"));
        SmartWalletTradeEventEntity event = baseEvent()
                .setSizeUsdc(new BigDecimal("150"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(3)
                .dedupedEntityCount(3)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile("0xfast")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xfast")
                .recommendedExitLane("FAST_EXIT_OR_SKIP")
                .replicabilityScore(new BigDecimal("0.22"))
                .earlyExitRate(new BigDecimal("0.78"))
                .settlementCompletionRate(new BigDecimal("0.10"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[BEHAVIOR_BLOCK]")));
    }

    @Test
    void shouldDownsizeStakeForHybridEarlyExitWallet() {
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setKellyStakeEnabled(false);

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xhybrid")
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80)
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.70"));
        SmartWalletTradeEventEntity event = baseEvent()
                .setSizeUsdc(new BigDecimal("100"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.80"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile("0xhybrid")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xhybrid")
                .recommendedExitLane("HYBRID_EARLY_EXIT")
                .replicabilityScore(new BigDecimal("0.58"))
                .earlyExitRate(new BigDecimal("0.45"))
                .settlementCompletionRate(new BigDecimal("0.40"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.36"), decision.getRecommendedStakeUsdc());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[BEHAVIOR_STAKE]")));
    }

    @Test
    void shouldRejectSingleWalletOverrideWhenWalletIsInObserveCooldown() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xcooldown")
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.80"))
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.72"))
                .setTotalTrades(120)
                .setObserveCooldownUntil(new Date(System.currentTimeMillis() + 60_000L))
                .setObserveCooldownReason("recent degradation");
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile("0xcooldown")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xcooldown")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.85"))
                .qualityScore(new BigDecimal("0.82"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[OBSERVE_COOLDOWN_BLOCK]")));
        assertTrue(decision.getStrategyChecks().stream()
                .filter(check -> "SINGLE_WALLET_OVERRIDE".equals(check.getStrategyKey()))
                .flatMap(check -> check.getReasons().stream())
                .anyMatch(reason -> reason.contains("[SINGLE_OVERRIDE_BLOCK_RECENT_DEGRADATION]")));
    }

    @Test
    void shouldRejectSingleWalletOverrideWhenBehaviorThresholdIsNotMet() {
        properties.getSignalRisk().setMinDistinctEntities(2);
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xbehavior")
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.80"))
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.72"))
                .setTotalTrades(120);
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile("0xbehavior")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xbehavior")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.40"))
                .qualityScore(new BigDecimal("0.45"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getStrategyChecks().stream()
                .filter(check -> "SINGLE_WALLET_OVERRIDE".equals(check.getStrategyKey()))
                .flatMap(check -> check.getReasons().stream())
                .anyMatch(reason -> reason.contains("[SINGLE_OVERRIDE_BLOCK_BEHAVIOR]")));
    }

    @Test
    void shouldNotBypassConsensusScoreFailureWithSingleWalletOverride() {
        properties.getSignalRisk().setMinConsensusScore(new BigDecimal("0.65"));
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xconsensus")
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.80"))
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.72"))
                .setTotalTrades(120);
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.60"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile("0xconsensus")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xconsensus")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.85"))
                .qualityScore(new BigDecimal("0.82"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getStrategyChecks().stream()
                .filter(check -> "SINGLE_WALLET_OVERRIDE".equals(check.getStrategyKey()))
                .flatMap(check -> check.getReasons().stream())
                .anyMatch(reason -> reason.contains("[SINGLE_OVERRIDE_BLOCK_CONSENSUS_SCORE]")));
    }

    @Test
    void shouldRejectSingleWalletOverrideWhenCanaryDoesNotSelectWallet() {
        properties.getSignalRisk().setMinDistinctEntities(2);
        properties.getSignalRisk().setEnableSingleWalletOverrideCanary(true);
        properties.getSignalRisk().setSingleWalletOverrideCanaryRate(BigDecimal.ZERO);
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xcanary")
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.80"))
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.72"))
                .setTotalTrades(120);
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile("0xcanary")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xcanary")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.85"))
                .qualityScore(new BigDecimal("0.82"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getStrategyChecks().stream()
                .filter(check -> "SINGLE_WALLET_OVERRIDE".equals(check.getStrategyKey()))
                .flatMap(check -> check.getReasons().stream())
                .anyMatch(reason -> reason.contains("[SINGLE_OVERRIDE_BLOCK_CANARY]")));
    }

    @Test
    void shouldDownsizeInsteadOfBlockingHighlyConcentratedMarket() {
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setEnableConcentrationGate(true);
        properties.getSignalRisk().setConcentrationHighRiskMode("DOWNSIZE");
        properties.getSignalRisk().setConcentrationHighRiskDownsizeRate(new BigDecimal("0.50"));

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xconc")
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80)
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.70"));
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("100"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.85"))
                .build();

        prepareCommonMocks(consensus);
        when(executionWalletContextLoader.load()).thenReturn(ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .minOrderUsdc(new BigDecimal("0.10"))
                .build());
        when(walletBehaviorProfileService.getProfile("0xconc")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xconc")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.80"))
                .qualityScore(new BigDecimal("0.78"))
                .stabilityScore(new BigDecimal("0.60"))
                .build());
        when(concentrationService.analyze(any(), any())).thenReturn(new MarketConcentrationService.ConcentrationSnapshot(
                "market-1",
                "YES",
                20,
                new BigDecimal("0.55"),
                new BigDecimal("0.78"),
                new BigDecimal("0.90"),
                3200,
                false,
                true,
                java.util.List.of("0xwhale"),
                System.currentTimeMillis()
        ));

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted());
        assertEquals(new BigDecimal("1.20"), decision.getRecommendedStakeUsdc());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[CONCENTRATION_DOWNSIZE]")));
    }

    @Test
    void shouldRejectClosedMarketLifecycle() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xclosed")
                .setWalletScore(new BigDecimal("0.85"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(50);
        SmartWalletTradeEventEntity event = baseEvent();
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(marketStateService.refreshMarket(org.mockito.ArgumentMatchers.eq("market-1"), any(), any()))
                .thenReturn(SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(true)
                        .closed(true)
                        .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[MARKET_CLOSED]")));
    }

    @Test
    void shouldRejectResolvedMarketLifecycle() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xresolved")
                .setWalletScore(new BigDecimal("0.85"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(50);
        SmartWalletTradeEventEntity event = baseEvent();
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(marketStateService.refreshMarket(org.mockito.ArgumentMatchers.eq("market-1"), any(), any()))
                .thenReturn(SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_RESOLVED)
                        .acceptingOrders(false)
                        .closed(true)
                        .resolvedOutcome("YES")
                        .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[MARKET_RESOLVED]")));
    }

    @Test
    void shouldRejectMarketNotAcceptingOrders() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xhalted")
                .setWalletScore(new BigDecimal("0.85"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(50);
        SmartWalletTradeEventEntity event = baseEvent();
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(marketStateService.refreshMarket(org.mockito.ArgumentMatchers.eq("market-1"), any(), any()))
                .thenReturn(SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(false)
                        .closed(false)
                        .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[MARKET_NOT_ACCEPTING_ORDERS]")));
    }

    @Test
    void shouldRejectPastEndAtMarketLifecycle() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xpast")
                .setWalletScore(new BigDecimal("0.85"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(50);
        SmartWalletTradeEventEntity event = baseEvent();
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(marketStateService.refreshMarket(org.mockito.ArgumentMatchers.eq("market-1"), any(), any()))
                .thenReturn(SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_CLOSED_UNRESOLVED)
                        .acceptingOrders(true)
                        .marketEndAt(System.currentTimeMillis() - 60_000L)
                        .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("past expected end time")));
    }

    @Test
    void shouldAmplifyStakeWhenTwoEntitiesInConsensus() {
        properties.getSignalRisk().setEnableConsensusStakeAmplifier(true);
        properties.getSignalRisk().setConsensusAmplifierTier2Multiplier(new BigDecimal("1.15"));
        properties.getSignalRisk().setConsensusAmplifierMaxMultiplier(new BigDecimal("1.50"));
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(60)
                .setRecentTradeCount7d(4)
                .setRecentWinRate7d(new BigDecimal("0.65"));
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2)
                .dedupedEntityCount(2)
                .consensusScore(new BigDecimal("0.75"))
                .build();

        prepareCommonMocks(consensus);

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted(),
                "Should be accepted. Reasons: " + decision.getReasons());
        assertTrue(decision.getReasons().stream().anyMatch(r -> r.contains("[CONSENSUS_AMPLIFIER]")),
                "Should log amplifier reason. Reasons: " + decision.getReasons());
    }

    @Test
    void shouldNotBlockHighQualitySingleWalletWhenConsensusScoreIsLow() {
        properties.getSignalRisk().setMinConsensusScore(new BigDecimal("0.65"));
        properties.getSignalRisk().setMinDistinctEntities(1);
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.85"))
                .setTotalTrades(150)
                .setRecentTradeCount7d(10)
                .setRecentWinRate7d(new BigDecimal("0.80"));
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.05"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile(any())).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.80"))
                .qualityScore(new BigDecimal("0.75"))
                .earlyExitRate(new BigDecimal("0.20"))
                .settlementCompletionRate(new BigDecimal("0.80"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted(),
                "HIGH_QUALITY_SINGLE_WALLET should accept regardless of consensusScore. Reasons: "
                        + decision.getReasons());
        assertTrue(decision.getMatchedStrategies().contains("HIGH_QUALITY_SINGLE_WALLET"));
    }

    private void prepareCommonMocks(ConsensusSignalView consensus) {
        when(manualAllowlistService.findActive(any())).thenReturn(java.util.Optional.empty());
        when(positionMapper.selectCount(any())).thenReturn(0L);
        when(positionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(followTaskMapper.selectCount(any())).thenReturn(0L);
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("100"));
        when(marketMetadataService.getByConditionId(any())).thenReturn(PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-1")
                .marketId("market-1")
                .endAt(System.currentTimeMillis() + 3_600_000L)
                .build());
        when(marketStateService.refreshMarket(any(), any(), any())).thenReturn(
                SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .marketEndAt(System.currentTimeMillis() + 3_600_000L)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .build());
        when(consensusSignalService.buildConsensusForEvent(any())).thenReturn(consensus);
        when(consensusSignalService.buildConsensusForEvent(any(), any())).thenReturn(consensus);
        when(leaderboardStateService.adjustWalletScore(
                any(SmartWalletProfileEntity.class),
                any(SmartWalletTradeEventEntity.class))).thenAnswer(invocation -> {
            SmartWalletProfileEntity profile = invocation.getArgument(0);
            return profile.getWalletScore();
        });
        when(walletArchetypeClassifierService.classify(any(), any())).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xabc")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .followFitScore(new BigDecimal("0.70"))
                .confidence(new BigDecimal("0.80"))
                .labelSampleSize(10)
                .build());
        when(executionWalletContextLoader.load()).thenReturn(ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .minOrderUsdc(BigDecimal.ONE)
                .build());
        when(polymarketApiClient.fetchBalance(any())).thenReturn(new BigDecimal("100"));
        when(tradeProfitabilityScoringService.estimate(any(), any(), any(), any()))
                .thenReturn(TradeProfitabilityScoringService.ProfitabilityEstimate.builder()
                        .estimatedNetProfitabilityUsdc(new BigDecimal("1.50"))
                        .estimatedFeeUsdc(new BigDecimal("0.10"))
                        .estimatedSlippageUsdc(new BigDecimal("0.20"))
                        .followProbability(new BigDecimal("0.70"))
                        .calibratedFollowProbability(new BigDecimal("0.72"))
                        .slippageSpikeProbability(new BigDecimal("0.10"))
                        .estimateSampleSize(10)
                        .build());
    }

    private SmartWalletProfileEntity baseProfile() {
        return new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setMonitorTier("HOT");
    }

    private SmartWalletTradeEventEntity baseEvent() {
        return new SmartWalletTradeEventEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.48"))
                .setOpenedAt(new Date());
    }
}
