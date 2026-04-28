package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
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
                orderMapper,
                tradeEventMapper,
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
    void shouldRejectHighQualitySingleWalletWhenWalletIsInObserveCooldown() {
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
                .filter(check -> "HIGH_QUALITY_SINGLE_WALLET".equals(check.getStrategyKey()))
                .flatMap(check -> check.getReasons().stream())
                .anyMatch(reason -> reason.contains("[SINGLE_OVERRIDE_BLOCK_RECENT_DEGRADATION]")));
    }

    @Test
    void shouldRejectHighQualitySingleWalletWhenBehaviorThresholdIsNotMet() {
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
                .filter(check -> "HIGH_QUALITY_SINGLE_WALLET".equals(check.getStrategyKey()))
                .flatMap(check -> check.getReasons().stream())
                .anyMatch(reason -> reason.contains("[SINGLE_OVERRIDE_BLOCK_BEHAVIOR]")));
    }

    @Test
    void shouldRejectHighQualitySingleWalletWhenCanaryDoesNotSelectWallet() {
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
                .filter(check -> "HIGH_QUALITY_SINGLE_WALLET".equals(check.getStrategyKey()))
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
    void shouldNotEnforceConcentrationGateWhenObservedHolderSampleIsTooSmall() {
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setEnableConcentrationGate(true);
        properties.getSignalRisk().setConcentrationMinObservedHoldersForGate(12);

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xlowcoverage")
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
        when(walletBehaviorProfileService.getProfile("0xlowcoverage")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xlowcoverage")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.80"))
                .qualityScore(new BigDecimal("0.78"))
                .stabilityScore(new BigDecimal("0.60"))
                .build());
        when(concentrationService.analyze(any(), any())).thenReturn(new MarketConcentrationService.ConcentrationSnapshot(
                "market-1",
                "YES",
                5,
                new BigDecimal("0.80"),
                new BigDecimal("0.95"),
                new BigDecimal("1.00"),
                7200,
                true,
                true,
                java.util.List.of("0xwhale"),
                System.currentTimeMillis()
        ));

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted());
        assertEquals(new BigDecimal("2.40"), decision.getRecommendedStakeUsdc());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("[CONCENTRATION_LOW_COVERAGE]")));
        assertFalse(decision.getReasons().stream().anyMatch(item -> item.contains("[EXTREME_CONCENTRATION_DOWNSIZE]")));
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

    // ─── HF_SCALPER archetype string mismatch tests ──────────────────────────────

    private void prepareHfScalperMocks(ConsensusSignalView consensus, long standardBinaryEndAt) {
        when(manualAllowlistService.findActive(any())).thenReturn(java.util.Optional.empty());
        when(positionMapper.selectCount(any())).thenReturn(0L);
        when(positionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(followTaskMapper.selectCount(any())).thenReturn(0L);
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("500"));
        when(walletArchetypeClassifierService.classify(any(), any())).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xhf")
                .archetype("HF_SCALPER")
                .followLane("HF_FOLLOW")
                .followFitScore(new BigDecimal("0.70"))
                .confidence(new BigDecimal("0.80"))
                .labelSampleSize(10)
                .build());
        when(walletBehaviorProfileService.getProfile(any())).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xhf")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.80"))
                .earlyExitRate(new BigDecimal("0.20"))
                .settlementCompletionRate(new BigDecimal("0.80"))
                .build());
        when(marketStateService.refreshMarket(any(), any(), any())).thenReturn(
                SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .marketEndAt(standardBinaryEndAt)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .build());
        when(leaderboardStateService.adjustWalletScore(
                any(SmartWalletProfileEntity.class),
                any(SmartWalletTradeEventEntity.class))).thenAnswer(invocation -> {
            SmartWalletProfileEntity p = invocation.getArgument(0);
            return p.getWalletScore();
        });
        when(consensusSignalService.buildConsensusForEvent(any())).thenReturn(consensus);
        when(consensusSignalService.buildConsensusForEvent(any(), any())).thenReturn(consensus);
        when(executionWalletContextLoader.load()).thenReturn(
                ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                        .minOrderUsdc(new BigDecimal("0.10"))
                        .build());
        when(tradeProfitabilityScoringService.estimate(any(), any(), any(), any()))
                .thenReturn(TradeProfitabilityScoringService.ProfitabilityEstimate.builder()
                        .estimatedNetProfitabilityUsdc(new BigDecimal("2.00"))
                        .estimatedFeeUsdc(BigDecimal.ZERO)
                        .estimatedSlippageUsdc(BigDecimal.ZERO)
                        .followProbability(new BigDecimal("0.70"))
                        .calibratedFollowProbability(new BigDecimal("0.72"))
                        .slippageSpikeProbability(BigDecimal.ZERO)
                        .estimateSampleSize(10)
                        .build());
    }

    @Test
    void shouldRejectHfScalperSignalWhenOlderThan45Seconds() {
        // HF_SCALPER wallets must enforce a 45s max signal age.
        // Bug: case "HF" in resolveEffectiveSignalAgeMs never matches "HF_SCALPER",
        // so the default baseMs (3600s) is used and a 75s old signal is incorrectly accepted.
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setKellyStakeEnabled(false);
        properties.getSignalRisk().setEnableConsensusStakeAmplifier(false);
        properties.getSignalRisk().setMaxSignalAgeSeconds(3600);

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xhf")
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80);
        // 75s old: older than the 45s HF limit, but younger than the 3600s default
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.48"))
                .setSizeUsdc(new BigDecimal("200"))
                .setOpenedAt(new Date(System.currentTimeMillis() - 75_000L));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2).dedupedEntityCount(2).consensusScore(new BigDecimal("0.80")).build();

        // 30h → STANDARD_BINARY (beyond 24h FAST_EVENT, below 36h LONG_HORIZON threshold)
        long standardBinaryEnd = System.currentTimeMillis() + 30 * 3_600_000L;
        prepareHfScalperMocks(consensus, standardBinaryEnd);

        SignalRiskDecision decision = service.evaluate(profile, event);

        // With bug ("HF" never matches "HF_SCALPER"): effectiveMax=3600s, 75s < 3600s → accepted
        // After fix ("HF_SCALPER" matches): effectiveMax=45s, 75s > 45s → rejected as stale
        assertFalse(decision.isAccepted(),
                "HF_SCALPER signal older than 45s must be rejected as stale. Reasons: " + decision.getReasons());
        assertTrue(decision.getReasons().stream().anyMatch(r -> r.contains("stale")),
                "Rejection reason must mention staleness. Reasons: " + decision.getReasons());
    }

    @Test
    void shouldReduceHfScalperStakeWhenSignalOlderThan30Seconds() {
        // HF_SCALPER wallets with signals older than 30s must have stake reduced to 25%.
        // Bug: "HF".equals(archetype.archetype()) never matches "HF_SCALPER", no reduction applied.
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setKellyStakeEnabled(false);
        properties.getSignalRisk().setEnableConsensusStakeAmplifier(false);
        properties.getSignalRisk().setMaxSignalAgeSeconds(300); // 300s base; HF cap = min(300s,45s) = 45s

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xhf")
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80);
        // 35s old: older than 30s HF penalty, but younger than 45s HF max → passes freshness, gets penalty
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.48"))
                .setSizeUsdc(new BigDecimal("100"))
                .setOpenedAt(new Date(System.currentTimeMillis() - 35_000L));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2).dedupedEntityCount(2).consensusScore(new BigDecimal("0.80")).build();

        long standardBinaryEnd = System.currentTimeMillis() + 30 * 3_600_000L;
        prepareHfScalperMocks(consensus, standardBinaryEnd);
        // Our capital = 100; set budget = capital so applyDynamicCapitalScale is neutral (scale=1.0)
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("100"));
        properties.getSignalRisk().setMaxTotalOpenCostUsdc(new BigDecimal("100"));

        SignalRiskDecision decision = service.evaluate(profile, event);

        // stake = availableCapital(100) * bankrollRate(0.03) * walletScore(0.80) = 2.40; HF >30s → 2.40 * 0.25 = 0.60
        // With bug: no HF reduction → stake remains 2.40
        assertTrue(decision.isAccepted(),
                "Signal is 35s old (< 45s HF max): must be accepted. Reasons: " + decision.getReasons());
        assertEquals(new BigDecimal("0.60"), decision.getRecommendedStakeUsdc(),
                "HF_SCALPER signal >30s old must cut stake to 25% of base (2.40×0.25=0.60). Reasons: "
                        + decision.getReasons());
        assertTrue(decision.getReasons().stream().anyMatch(r -> r.contains("[ARCHETYPE_STAKE]")),
                "Must log ARCHETYPE_STAKE reason. Reasons: " + decision.getReasons());
    }

    @Test
    void shouldBaseStakeOnAvailableCapitalNotSignalSize() {
        // Bug: preliminaryStake = triggerSize * walletScore * bankrollUtilizationRate
        //      = 1000 * 0.80 * 0.03 = 24.00  (anchored to signal wallet trade size — over-leveraged)
        // Fix: preliminaryStake = availableCapital * bankrollUtilizationRate * walletScore
        //      = 100 * 0.03 * 0.80 = 2.40   (anchored to our own capital — correct)
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setKellyStakeEnabled(false);
        properties.getSignalRisk().setEnableConsensusStakeAmplifier(false);
        // Set budget = capital so applyDynamicCapitalScale is a no-op (scale = capital/budget = 1.0)
        properties.getSignalRisk().setMaxTotalOpenCostUsdc(new BigDecimal("100"));

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletAddress("0xhf")
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80);
        // Smart wallet traded 1000 USDC; our account has only 100 USDC — stake must track OUR capital
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.48"))
                .setSizeUsdc(new BigDecimal("1000"))
                .setOpenedAt(new Date());
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2).dedupedEntityCount(2).consensusScore(new BigDecimal("0.80")).build();

        long standardBinaryEnd = System.currentTimeMillis() + 30 * 3_600_000L;
        prepareHfScalperMocks(consensus, standardBinaryEnd);
        // Override: our account has only 100 USDC, not the 500 set by prepareHfScalperMocks
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("100"));

        SignalRiskDecision decision = service.evaluate(profile, event);

        // Fixed: 100 * 0.03 * 0.80 = 2.40 (capital-anchored)
        // Buggy: 1000 * 0.80 * 0.03 = 24.00 (signal-size-anchored, 24x over-leveraged)
        // (applyDynamicCapitalScale is neutral: scale = availableCapital/maxTotalOpenCost = 100/100 = 1.0)
        assertTrue(decision.isAccepted(),
                "Signal should be accepted. Reasons: " + decision.getReasons());
        assertEquals(new BigDecimal("2.40"), decision.getRecommendedStakeUsdc(),
                "Stake must be anchored to availableCapital (100*0.03*0.80=2.40), not triggerSize. Reasons: "
                        + decision.getReasons());
    }

    @Test
    void shouldUseLongHorizonMinHoursConfigInsteadOfHalfMaxHoursToMarketEnd() {
        // Bug: LONG_HORIZON threshold = maxHoursToMarketEnd / 2 (coupled).
        // Fix: use independent longHorizonMinHours config (default 48h).
        //
        // Test case: a 40h-remaining market.
        // With maxHoursToMarketEnd=72 and the bug: threshold = 72/2 = 36h → 40h > 36h → LONG_HORIZON (wrong)
        // With longHorizonMinHours=48 (fix): 40h < 48h → STANDARD_BINARY (correct — 40h is not "long horizon")
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setKellyStakeEnabled(false);
        properties.getSignalRisk().setEnableConsensusStakeAmplifier(false);
        // Set longHorizonMinHours to 48h (independent of maxHoursToMarketEnd=72)
        properties.getSignalRisk().setLongHorizonMinHours(48);

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80);
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("100"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2).dedupedEntityCount(2).consensusScore(new BigDecimal("0.80")).build();

        // 40h remaining: should be STANDARD_BINARY (not LONG_HORIZON) with longHorizonMinHours=48
        long fortyHoursFromNow = System.currentTimeMillis() + 40 * 3_600_000L;
        prepareCommonMocks(consensus);
        when(marketStateService.refreshMarket(any(), any(), any())).thenReturn(
                SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .marketEndAt(fortyHoursFromNow)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        // With the bug (threshold = 72/2 = 36h): 40h > 36h → LONG_HORIZON → stake multiplied by 0.50
        // With the fix (threshold = longHorizonMinHours = 48h): 40h < 48h → STANDARD_BINARY → no multiplier
        assertTrue(decision.getReasons().stream().anyMatch(r -> r.contains("STANDARD_BINARY")),
                "A 40h market with longHorizonMinHours=48 must be classified as STANDARD_BINARY, not LONG_HORIZON. Reasons: "
                        + decision.getReasons());
        assertFalse(decision.getReasons().stream().anyMatch(r -> r.contains("LONG_HORIZON")),
                "A 40h market must NOT be classified as LONG_HORIZON when longHorizonMinHours=48. Reasons: "
                        + decision.getReasons());
    }

    private void prepareCommonMocks(ConsensusSignalView consensus) {
        when(manualAllowlistService.findActive(any())).thenReturn(java.util.Optional.empty());
        when(positionMapper.selectCount(any())).thenReturn(0L);
        when(positionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(followTaskMapper.selectCount(any())).thenReturn(0L);
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("100"));
        // Set budget = available capital so applyDynamicCapitalScale is neutral (scale = capital/budget = 1.0).
        // Tests that need capital-scaling behavior must override this explicitly.
        properties.getSignalRisk().setMaxTotalOpenCostUsdc(new BigDecimal("100"));
        // 30h: beyond FAST_EVENT window (<24h) and below LONG_HORIZON threshold (>maxHoursToMarketEnd/2 = 36h)
        long farFutureEndAt = System.currentTimeMillis() + 30 * 3_600_000L;
        when(marketMetadataService.getByConditionId(any())).thenReturn(PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-1")
                .marketId("market-1")
                .endAt(farFutureEndAt)
                .build());
        when(marketStateService.refreshMarket(any(), any(), any())).thenReturn(
                SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .marketEndAt(farFutureEndAt)
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

    @Test
    void shouldNotCountThemeCorrelationWhenOverlapIsOnlyStopWords() {
        // Bug: "Will bitcoin reach 2024 high?" and "Will ethereum reach 2024 high?"
        // overlap on {"will","2024","high","reach"} — 4 tokens ≥ threshold(2) → false positive penalty
        // Fix: stop-words {"will","2024","2025","what","that","this","with","from","when","have","been","more"}
        // filtered out → only unique topic tokens remain, overlap = {} < 2 → no penalty
        properties.getSignalRisk().setEnableConsensusGate(false);
        properties.getSignalRisk().setEnableSingleWalletOverride(false);
        properties.getSignalRisk().setKellyStakeEnabled(false);
        properties.getSignalRisk().setEnableConsensusStakeAmplifier(false);

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.80"))
                .setWinRate(new BigDecimal("0.70"))
                .setTotalTrades(80);
        // Signal event: a bitcoin question
        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.48"))
                .setSizeUsdc(new BigDecimal("100"))
                .setQuestion("Will bitcoin reach 2024 high?")
                .setOpenedAt(new Date());
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(2).dedupedEntityCount(2).consensusScore(new BigDecimal("0.80")).build();

        prepareCommonMocks(consensus);

        // Existing open position: an ethereum question sharing only stop-words with the bitcoin question.
        // Must set marketInfo so that position.getQuestion() returns the text (derived field).
        com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity marketInfo =
                new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity()
                        .setQuestion("Will ethereum reach 2024 high?");
        com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity existingPosition =
                new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                        .setMarketInfo(marketInfo);
        when(positionMapper.selectList(any())).thenReturn(java.util.List.of(existingPosition));

        SignalRiskDecision decision = service.evaluate(profile, event);

        // With bug: "will","2024","high","reach" all pass the ≥4 char filter → 4 shared tokens ≥ 2 → THEME_CORRELATION penalty
        // With fix: stop-words {"will","2024","high","reach"} filtered → only {"bitcoin"} vs {"ethereum"} → overlap=0 < 2 → no penalty
        assertFalse(decision.getReasons().stream().anyMatch(r -> r.contains("[THEME_CORRELATION]")),
                "Only stop-word overlap must NOT trigger theme correlation. Reasons: " + decision.getReasons());
    }

    @Test
    void shouldRejectObviouslyFarFutureMarketEndAtWhenConfiguredThresholdLower() {
        // Bug: OBVIOUSLY_FUTURE_MARKET_END_MS is hardcoded to 365 days.
        // Operators cannot tighten the data-quality guard without redeployment.
        // Fix: read obviouslyFutureMarketEndDays from config.
        //
        // Market ends 31 days from now:
        //   hardcoded 365d: 31d < 365d → not rejected → accepted
        //   config 30d:     31d > 30d  → rejected as obviously future
        properties.getSignalRisk().setObviouslyFutureMarketEndDays(30);  // tighten to 30 days

        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.80"))
                .setTotalTrades(120)
                .setRecentTradeCount7d(8)
                .setRecentWinRate7d(new BigDecimal("0.75"));
        SmartWalletTradeEventEntity event = baseEvent().setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(3).dedupedEntityCount(3).consensusScore(new BigDecimal("0.80")).build();

        prepareCommonMocks(consensus);
        // Override market end to 31 days from now (beyond configured 30d threshold)
        long endAt31Days = System.currentTimeMillis() + 31L * 24L * 3_600_000L;
        when(marketMetadataService.getByConditionId(any())).thenReturn(
                PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("market-1").marketId("market-1").endAt(endAt31Days).build());
        when(marketStateService.refreshMarket(any(), any(), any())).thenReturn(
                SmartWalletMarketStateService.MarketStateSnapshot.builder()
                        .marketId("market-1")
                        .marketLifecycle(SmartWalletMarketStateService.LIFECYCLE_ACTIVE)
                        .acceptingOrders(true)
                        .marketEndAt(endAt31Days)
                        .normalizedCategory(SmartWalletMarketStateService.CATEGORY_OTHER)
                        .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted(),
                "Market 31 days out must be rejected when obviouslyFutureMarketEndDays=30. Reasons: "
                        + decision.getReasons());
        assertTrue(decision.getReasons().stream().anyMatch(r -> r.contains("MARKET_END_AT_DATA_QUALITY")),
                "Must contain [MARKET_END_AT_DATA_QUALITY] reason. Got: " + decision.getReasons());
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
