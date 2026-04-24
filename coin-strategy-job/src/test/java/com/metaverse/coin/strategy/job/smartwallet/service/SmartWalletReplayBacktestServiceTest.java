package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletReplayBacktestResult;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletReplayBacktestServiceTest {

    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletProfileStoreService profileStoreService;
    @Mock
    private SmartWalletSignalRiskService signalRiskService;
    @Mock
    private WalletBehaviorProfileService walletBehaviorProfileService;

    @Test
    void shouldCompareBaselineAndCandidateMetricsAcrossReplayWindow() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getSignalRisk().setEnableSingleWalletOverride(true);
        properties.getSignalRisk().setQualityWeightedConsensusEnabled(true);
        properties.getSignalRisk().setExitSignalProviderMode("SCORING");
        properties.getSignalRisk().setPolicyTag("candidate-v2");

        SmartWalletTradeEventEntity event = new SmartWalletTradeEventEntity()
                .setId(1L)
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.44"))
                .setSizeUsdc(new BigDecimal("200"))
                .setOpenedAt(new Date());
        SmartWalletTradeEffectLabelEntity label = new SmartWalletTradeEffectLabelEntity()
                .setSourceEventId(1L)
                .setPaperStakeUsdc(new BigDecimal("10"))
                .setNetPnlRule(new BigDecimal("2.50"))
                .setUpdatedAt(new Date());
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE)
                .setWinRate(new BigDecimal("0.80"))
                .setRecentWinRate30d(new BigDecimal("0.78"))
                .setRecentWinRate7d(new BigDecimal("0.75"))
                .setRecentTradeCount7d(8)
                .setRecentTradeCount30d(20)
                .setRealizedPnlUsdc(new BigDecimal("3000"))
                .setTotalTrades(120)
                .setAverageRoiRate(new BigDecimal("0.16"))
                .setMaxDrawdownRate(new BigDecimal("0.10"))
                .setWalletScore(new BigDecimal("0.90"));

        when(tradeEventMapper.selectList(any())).thenReturn(List.of(event));
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(List.of(label));
        when(profileMapper.selectList(any())).thenReturn(List.of(profile));
        when(walletBehaviorProfileService.getProfile("0xabc")).thenReturn(WalletBehaviorProfileView.builder()
                .walletAddress("0xabc")
                .replicabilityScore(new BigDecimal("0.82"))
                .qualityScore(new BigDecimal("0.78"))
                .stabilityScore(new BigDecimal("0.74"))
                .behaviorComposite(new BigDecimal("0.79"))
                .recommendedExitLane("FOLLOW_ARCHETYPE")
                .build());
        doAnswer(invocation -> null).when(profileStoreService).applyQualificationState(any(), anyBoolean());
        when(signalRiskService.evaluate(any(), any(), any())).thenAnswer(invocation -> {
            SmartWalletProperties.SignalRisk risk = invocation.getArgument(2);
            if (risk.isEnableSingleWalletOverride()) {
                return SignalRiskDecision.builder()
                        .accepted(true)
                        .overrideApplied(true)
                        .recommendedStakeUsdc(new BigDecimal("20"))
                        .reasons(List.of("[SINGLE_OVERRIDE_PASS]"))
                        .build();
            }
            return SignalRiskDecision.builder()
                    .accepted(false)
                    .overrideApplied(false)
                    .recommendedStakeUsdc(BigDecimal.ZERO)
                    .reasons(List.of("Consensus wallet count is below threshold."))
                    .build();
        });

        SmartWalletReplayBacktestService service = new SmartWalletReplayBacktestService(
                properties,
                tradeEventMapper,
                tradeEffectLabelMapper,
                profileMapper,
                new SmartWalletScoringService(),
                profileStoreService,
                signalRiskService,
                walletBehaviorProfileService
        );
        SmartWalletReplayBacktestRequest request = new SmartWalletReplayBacktestRequest();
        request.setWindowDays(List.of(30));
        request.setMaxEventsPerWindow(100);

        SmartWalletReplayBacktestResult result = service.replay(request);

        assertEquals(1, result.getWindows().size());
        assertEquals(0, result.getWindows().get(0).getBaseline().getAcceptedEvents());
        assertEquals(1, result.getWindows().get(0).getCandidate().getAcceptedEvents());
        assertEquals(1, result.getWindows().get(0).getCandidate().getOverrideCount());
        assertEquals("RULES", result.getWindows().get(0).getBaseline().getExitMode());
        assertEquals("SCORING", result.getWindows().get(0).getCandidate().getExitMode());
        assertEquals("candidate-v2", result.getWindows().get(0).getCandidate().getPolicyTag());
        assertTrue(result.getWindows().get(0).getCandidate().getRealizedPnlUsdc().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(result.getWindows().get(0).getBaseline().getSkipReasonDistribution().containsKey("CONSENSUS"));
    }
}
