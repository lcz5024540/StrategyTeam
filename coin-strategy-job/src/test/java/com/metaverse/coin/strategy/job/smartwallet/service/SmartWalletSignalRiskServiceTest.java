package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
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

    private SmartWalletProperties properties;
    private SmartWalletSignalRiskService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        properties.getSignalRisk().setEntryStrategyRouteMode("ANY");
        properties.getSignalRisk().setEnabledEntryStrategies(java.util.List.of("CONSENSUS", "SINGLE_WALLET_OVERRIDE"));
        service = new SmartWalletSignalRiskService(
                properties,
                executionWalletContextLoader,
                consensusSignalService,
                positionMapper,
                followTaskMapper,
                marketMetadataService,
                accountingService,
                tradeProfitabilityScoringService,
                concentrationService,
                leaderboardStateService,
                new SmartWalletEntryStrategyRouter(),
                marketInfoService,
                kellyStakeCalculator,
                polymarketApiClient,
                walletBehaviorProfileService
        );
    }

    @Test
    void shouldAllowOpenWhenSingleWalletOverrideMatches() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.95"))
                .setWinRate(new BigDecimal("0.80"))
                .setTotalTrades(120);
        SmartWalletTradeEventEntity event = baseEvent()
                .setSizeUsdc(new BigDecimal("300"));
        ConsensusSignalView consensus = ConsensusSignalView.builder()
                .distinctWallets(1)
                .dedupedEntityCount(1)
                .consensusScore(new BigDecimal("0.90"))
                .build();

        prepareCommonMocks(consensus);
        when(walletBehaviorProfileService.getProfile(any())).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .replicabilityScore(new BigDecimal("0.80"))
                .earlyExitRate(new BigDecimal("0.20"))
                .settlementCompletionRate(new BigDecimal("0.80"))
                .build());

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertTrue(decision.isAccepted());
        assertTrue(decision.isOverrideApplied());
        assertTrue(decision.getMatchedStrategies().contains("SINGLE_WALLET_OVERRIDE"));
    }

    @Test
    void shouldRejectWhenNoEntryStrategyMatches() {
        SmartWalletProfileEntity profile = baseProfile()
                .setWalletScore(new BigDecimal("0.70"))
                .setWinRate(new BigDecimal("0.60"))
                .setTotalTrades(20);
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
                .setTotalTrades(90);
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
                .setTotalTrades(80);
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

    private void prepareCommonMocks(ConsensusSignalView consensus) {
        when(positionMapper.selectCount(any())).thenReturn(0L);
        when(positionMapper.selectList(any())).thenReturn(java.util.List.of());
        when(followTaskMapper.selectCount(any())).thenReturn(0L);
        when(accountingService.availableCapitalUsdc()).thenReturn(new BigDecimal("100"));
        when(marketMetadataService.getByConditionId(any())).thenReturn(PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-1")
                .marketId("market-1")
                .endAt(System.currentTimeMillis() + 3_600_000L)
                .build());
        when(consensusSignalService.buildConsensusForEvent(any())).thenReturn(consensus);
        when(leaderboardStateService.adjustWalletScore(any(), any())).thenAnswer(invocation -> {
            SmartWalletProfileEntity profile = invocation.getArgument(0);
            return profile.getWalletScore();
        });
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
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);
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
