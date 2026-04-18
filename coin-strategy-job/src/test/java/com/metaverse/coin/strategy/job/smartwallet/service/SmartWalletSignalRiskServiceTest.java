package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
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
                new SmartWalletEntryStrategyRouter()
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

        SignalRiskDecision decision = service.evaluate(profile, event);

        assertFalse(decision.isAccepted());
        assertTrue(decision.getMatchedStrategies().isEmpty());
        assertTrue(decision.getReasons().stream().anyMatch(item -> item.contains("Consensus wallet count")));
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
