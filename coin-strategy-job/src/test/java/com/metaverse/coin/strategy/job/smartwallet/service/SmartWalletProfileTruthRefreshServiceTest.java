package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletLeaderTradeOutcomeMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderAnalysisMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeOrderMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletLeaderTradeOutcomeEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeOrderEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketWalletMetricsRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletProfileTruthRefreshResult;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletProfileTruthRefreshServiceTest {

    @Mock
    private SmartWalletLeaderTradeOutcomeMapper leaderOutcomeMapper;
    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletTradeOrderMapper tradeOrderMapper;
    @Mock
    private SmartWalletOrderAnalysisMapper orderAnalysisMapper;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private SmartWalletScoringService scoringService;
    @Mock
    private SmartWalletProfileStoreService profileStoreService;

    private SmartWalletProfileTruthRefreshService service;

    @BeforeEach
    void setUp() {
        service = new SmartWalletProfileTruthRefreshService(
                leaderOutcomeMapper,
                profileMapper,
                tradeOrderMapper,
                orderAnalysisMapper,
                marketInfoService,
                scoringService,
                profileStoreService
        );
    }

    @Test
    void shouldBuildProfileMetricsFromTruthOutcomesInsteadOfLegacyWinRate() {
        when(leaderOutcomeMapper.selectList(any())).thenReturn(List.of(
                truthOutcome("0xabc", "market-1", "YES", "10", "100", "0.1000", true, 1_000_000L),
                truthOutcome("0xabc", "market-2", "NO", "-8", "40", "-0.2000", false, 2_000_000L)
        ));

        PolymarketWalletMetricsRequest metrics = service.buildTruthMetrics("0xabc", "leader", null);

        assertEquals("0xabc", metrics.getWalletAddress());
        assertEquals("leader", metrics.getAlias());
        assertEquals(2, metrics.getTotalTrades());
        assertEquals(new BigDecimal("0.5000"), metrics.getWinRate());
        assertEquals(new BigDecimal("2.0000"), metrics.getRealizedPnlUsdc());
        assertEquals(new BigDecimal("70.0000"), metrics.getAverageSizeUsdc());
    }

    @Test
    void shouldMarkProfileUntrustedWhenLegacyShowsPerfectButClosedLossIsMissing() {
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setId(7L)
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setAlias("leader")
                .setWinRate(new BigDecimal("1.0000"))
                .setWalletScore(new BigDecimal("0.9000"));
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(leaderOutcomeMapper.selectList(any())).thenReturn(List.of(
                truthOutcome("0xabc", "market-1", "YES", "10", "100", "0.1000", true, 1_000_000L),
                truthOutcome("0xabc", "market-2", "NO", "-8", "40", "-0.2000", false, 2_000_000L)
        ));
        when(leaderOutcomeMapper.selectCount(any())).thenReturn(0L);
        SmartWalletTradeOrderEntity loss = new SmartWalletTradeOrderEntity()
                .setWalletAddress("0xabc")
                .setMarketInfoId(20L)
                .setTotalPnlUsdc(new BigDecimal("-8"))
                .setStatus("CLOSED")
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setId(20L)
                        .setMarketId("market-2")
                        .setOutcome("NO"));
        when(tradeOrderMapper.selectList(any())).thenReturn(List.of(loss));
        when(marketInfoService.populateTradeOrders(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderAnalysisMapper.selectList(any())).thenReturn(List.of());
        when(scoringService.score(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new BigDecimal("0.4200"));

        SmartWalletProfileTruthRefreshResult result = service.refreshProfile("0xabc");

        assertTrue(result.refreshed());
        assertEquals("TRUTH_OUTCOME", result.status());
        assertEquals(2, result.truthSampleCount());
        assertEquals(1, result.missingLossCount());
        assertEquals(new BigDecimal("0.5000"), profile.getWinRate());
        assertEquals(new BigDecimal("0.4200"), profile.getWalletScore());
        assertTrue(profile.getObserveCooldownReason().contains("UNTRUSTED_PROFILE_WIN_RATE"));
        verify(profileStoreService).applyQualificationState(profile);
        verify(profileMapper).updateById(profile);
    }

    private SmartWalletLeaderTradeOutcomeEntity truthOutcome(String wallet,
                                                             String marketId,
                                                             String outcome,
                                                             String pnl,
                                                             String cost,
                                                             String roi,
                                                             boolean win,
                                                             long lastTradeAt) {
        return new SmartWalletLeaderTradeOutcomeEntity()
                .setWalletAddress(wallet)
                .setMarketId(marketId)
                .setOutcome(outcome)
                .setBuyCostUsdc(new BigDecimal(cost))
                .setEstimatedRealizedPnlUsdc(new BigDecimal(pnl))
                .setEstimatedRoiRate(new BigDecimal(roi))
                .setIsWin(win)
                .setOutcomeStatus(win
                        ? LeaderTradeOutcomeRebuildService.STATUS_CLOSED_WIN
                        : LeaderTradeOutcomeRebuildService.STATUS_CLOSED_LOSS)
                .setSourceConfidence(new BigDecimal("0.9000"))
                .setLastTradeAt(new Date(lastTradeAt));
    }
}
