package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletBehaviorProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletWalletFeatureDailyMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletBehaviorProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderAnalysisEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletWalletFeatureDailyEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.job.smartwallet.model.WalletBehaviorProfileView;
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
class WalletBehaviorProfileServiceTest {

    @Mock
    private SmartWalletWalletFeatureDailyMapper walletFeatureDailyMapper;
    @Mock
    private SmartWalletBehaviorProfileMapper behaviorProfileMapper;
    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    @Mock
    private SmartWalletOrderAnalysisService orderAnalysisService;
    @Mock
    private WalletArchetypeClassifierService walletArchetypeClassifierService;

    private WalletBehaviorProfileService service;

    @BeforeEach
    void setUp() {
        service = new WalletBehaviorProfileService(
                behaviorProfileMapper,
                profileMapper,
                walletFeatureDailyMapper,
                tradeEffectLabelMapper,
                orderAnalysisService,
                walletArchetypeClassifierService
        );
    }

    @Test
    void shouldRecommendEarlyTakeProfitForProfitFirstWallet() {
        SmartWalletProfileEntity profile = new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setAlias("alpha")
                .setWinRate(new BigDecimal("0.70"))
                .setRecentWinRate30d(new BigDecimal("0.68"))
                .setAverageRoiRate(new BigDecimal("0.18"))
                .setRealizedPnlUsdc(new BigDecimal("250.00"))
                .setLastTradeAt(new Date(System.currentTimeMillis() - 2L * 24 * 3600_000L))
                .setMaxDrawdownRate(new BigDecimal("0.12"));
        when(walletFeatureDailyMapper.selectOne(any())).thenReturn(new SmartWalletWalletFeatureDailyEntity()
                .setTrades7d(12)
                .setTrades30d(42)
                .setFollowableRate30d(new BigDecimal("0.65"))
                .setPnl30d(new BigDecimal("160.00")));
        when(walletArchetypeClassifierService.classify("0xabc", profile)).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xabc")
                .archetype("SWING")
                .followLane("SWING_FOLLOW")
                .followFitScore(new BigDecimal("0.78"))
                .medianInterTradeMinutes(new BigDecimal("120"))
                .build());
        when(orderAnalysisService.analyzeAndPersist("0xabc")).thenReturn(List.of(
                order("EARLY_PROFIT", "0.35", "100", "120", true),
                order("EARLY_PROFIT", "0.25", "100", "110", true),
                order("SETTLED_WIN", "0.20", "100", "480", true),
                order("SETTLED_WIN", "0.15", "100", "520", true),
                order("SETTLED_LOSS", "-0.20", "100", "510", false),
                order("SETTLED_LOSS", "-0.10", "100", "540", false)
        ));
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(List.of(
                label(true, true, "4", "6"),
                label(true, true, "5", "4"),
                label(true, false, "3", "-1"),
                label(false, false, "-2", "-3")
        ));
        when(profileMapper.selectList(any())).thenReturn(List.of(
                new SmartWalletProfileEntity().setRealizedPnlUsdc(new BigDecimal("100")),
                new SmartWalletProfileEntity().setRealizedPnlUsdc(new BigDecimal("200")),
                new SmartWalletProfileEntity().setRealizedPnlUsdc(new BigDecimal("250"))
        ));

        WalletBehaviorProfileView view = service.buildProfile(profile);

        assertTrue(view.summary().contains("recommendedExitLane=EARLY_TAKE_PROFIT"));
        assertEquals("EARLY_TAKE_PROFIT", view.recommendedExitLane());
        assertEquals("LATENCY_ALPHA", view.winSourceType());
        assertEquals(new BigDecimal("0.5000"), view.profitEarlyExitRate());
        assertEquals(new BigDecimal("0.0000"), view.lossEarlyExitRate());
        assertEquals(new BigDecimal("0.3333"), view.earlyExitRate());
        assertEquals(new BigDecimal("0.5000"), view.settlementWinRate());
        assertTrue(view.qualityScore().compareTo(new BigDecimal("0.60")) > 0);
        assertTrue(view.replicabilityScore().compareTo(new BigDecimal("0.60")) > 0);
        assertEquals(false, view.highFrequencyTradingAddress());
        verify(behaviorProfileMapper).insert(any(SmartWalletBehaviorProfileEntity.class));
    }

    private SmartWalletOrderAnalysisEntity order(String exitStyle,
                                                 String roiRate,
                                                 String investedUsdc,
                                                 String holdMinutes,
                                                 boolean isWin) {
        BigDecimal roi = new BigDecimal(roiRate);
        BigDecimal invested = new BigDecimal(investedUsdc);
        return new SmartWalletOrderAnalysisEntity()
                .setWalletAddress("0xabc")
                .setExitStyle(exitStyle)
                .setRoiRate(roi)
                .setInvestedUsdc(invested)
                .setRealizedPnlUsdc(roi.multiply(invested))
                .setHoldMinutes(new BigDecimal(holdMinutes))
                .setIsWin(isWin);
    }

    private SmartWalletTradeEffectLabelEntity label(Boolean positive30s,
                                                    Boolean positive5m,
                                                    String netPnl30s,
                                                    String netPnl5m) {
        return new SmartWalletTradeEffectLabelEntity()
                .setIsPositiveEv30s(positive30s)
                .setIsPositiveEv5m(positive5m)
                .setNetPnl30s(new BigDecimal(netPnl30s))
                .setNetPnl5m(new BigDecimal(netPnl5m));
    }
}
