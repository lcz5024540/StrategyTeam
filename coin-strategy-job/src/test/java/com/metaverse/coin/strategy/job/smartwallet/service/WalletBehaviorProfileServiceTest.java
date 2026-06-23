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
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletArchetypeView;
import com.metaverse.coin.strategy.common.service.smartwallet.model.WalletBehaviorProfileView;
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
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    void shouldClassifyExpandedArchetypesFromDeduplicatedOrderEvidence() {
        SmartWalletProfileEntity profile = baseProfile("0xcase");
        when(profileMapper.selectList(any())).thenReturn(pnlDistribution());
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(List.of());
        when(walletArchetypeClassifierService.classify(anyString(), any())).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xcase")
                .archetype("NOISE_OR_INSUFFICIENT")
                .followLane("OBSERVE_ONLY")
                .followFitScore(new BigDecimal("0.50"))
                .medianInterTradeMinutes(new BigDecimal("240"))
                .build());

        assertEquals("LOW_FREQ_VALUE", classify(profile,
                feature(6, 12, 12, new BigDecimal("0.12"), new BigDecimal("0.50")),
                datedOrders(8, "EARLY_PROFIT", 300, 360, 2)));
        assertEquals("SETTLEMENT_HOLDER", classify(profile,
                feature(10, 20, 20, new BigDecimal("0.20"), new BigDecimal("0.55")),
                datedOrders(12, "SETTLED_WIN", 360, 360, 2)));
        assertEquals("HF_SCALPER", classify(profile,
                feature(22, 48, 36, new BigDecimal("0.20"), new BigDecimal("0.50")),
                datedOrders(48, "EARLY_PROFIT", 30, 30, 1)));
        assertEquals("THEMATIC", classify(profile,
                feature(8, 18, 4, new BigDecimal("0.72"), new BigDecimal("0.52")),
                datedOrders(18, "EARLY_PROFIT", 180, 180, 2)));
        assertEquals("EVENT_DRIVEN", classify(profile,
                feature(8, 18, 12, new BigDecimal("0.40"), new BigDecimal("0.52")),
                datedOrders(18, "EARLY_PROFIT", 120, 120, 2)));
        assertEquals("RISK_CONTROL", classify(profile,
                feature(7, 14, 12, new BigDecimal("0.20"), new BigDecimal("0.40")),
                mixedRiskOrders()));
        assertEquals("SWING", classify(profile,
                feature(7, 14, 12, new BigDecimal("0.20"), new BigDecimal("0.70")),
                datedOrders(14, "EARLY_PROFIT", 180, 120, 2),
                List.of(label(false, true, "-1", "3"), label(false, true, "-2", "4"), label(true, true, "1", "2"), label(false, true, "-1", "5"))));
        assertEquals("NOISE_OR_INSUFFICIENT", classify(profile,
                feature(2, 4, 4, new BigDecimal("0.10"), new BigDecimal("0.10")),
                datedOrders(4, "UNKNOWN", 180, 120, 2)));
    }

    @Test
    void shouldNotClassifyLowFrequencyMultiFillWalletAsHf() {
        SmartWalletProfileEntity profile = baseProfile("0xlowfill");
        when(profileMapper.selectList(any())).thenReturn(pnlDistribution());
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(List.of());
        when(walletArchetypeClassifierService.classify(anyString(), any())).thenReturn(WalletArchetypeView.builder()
                .walletAddress("0xlowfill")
                .archetype("HF_SCALPER")
                .followLane("HF_FOLLOW")
                .followFitScore(new BigDecimal("0.60"))
                .medianInterTradeMinutes(new BigDecimal("20"))
                .build());

        when(walletFeatureDailyMapper.selectOne(any())).thenReturn(feature(25, 80, 8, new BigDecimal("0.20"), new BigDecimal("0.45")));
        when(orderAnalysisService.analyzeAndPersist("0xlowfill")).thenReturn(datedOrders(8, "EARLY_PROFIT", 360, 720, 5));
        WalletBehaviorProfileView view = service.buildProfile(profile);

        assertEquals("LOW_FREQ_VALUE", view.archetype());
        assertEquals(false, view.highFrequencyTradingAddress());
        assertTrue(view.archetypeReason().contains("Deduplicated order count is low"));
    }

    private String classify(SmartWalletProfileEntity profile,
                            SmartWalletWalletFeatureDailyEntity feature,
                            List<SmartWalletOrderAnalysisEntity> orders) {
        return classify(profile, feature, orders, List.of());
    }

    private String classify(SmartWalletProfileEntity profile,
                            SmartWalletWalletFeatureDailyEntity feature,
                            List<SmartWalletOrderAnalysisEntity> orders,
                            List<SmartWalletTradeEffectLabelEntity> labels) {
        when(walletFeatureDailyMapper.selectOne(any())).thenReturn(feature);
        when(orderAnalysisService.analyzeAndPersist(profile.getWalletAddress())).thenReturn(orders);
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(labels);
        return service.buildProfile(profile).archetype();
    }

    private SmartWalletProfileEntity baseProfile(String walletAddress) {
        return new SmartWalletProfileEntity()
                .setWalletAddress(walletAddress)
                .setAlias("alpha")
                .setWinRate(new BigDecimal("0.64"))
                .setRecentWinRate30d(new BigDecimal("0.62"))
                .setAverageRoiRate(new BigDecimal("0.12"))
                .setRealizedPnlUsdc(new BigDecimal("250.00"))
                .setLastTradeAt(new Date(System.currentTimeMillis() - 2L * 24 * 3600_000L))
                .setMaxDrawdownRate(new BigDecimal("0.12"));
    }

    private List<SmartWalletProfileEntity> pnlDistribution() {
        return List.of(
                new SmartWalletProfileEntity().setRealizedPnlUsdc(new BigDecimal("100")),
                new SmartWalletProfileEntity().setRealizedPnlUsdc(new BigDecimal("200")),
                new SmartWalletProfileEntity().setRealizedPnlUsdc(new BigDecimal("300"))
        );
    }

    private SmartWalletWalletFeatureDailyEntity feature(int trades7d,
                                                        int trades30d,
                                                        int markets30d,
                                                        BigDecimal concentration,
                                                        BigDecimal followableRate) {
        return new SmartWalletWalletFeatureDailyEntity()
                .setTrades7d(trades7d)
                .setTrades30d(trades30d)
                .setMarkets30d(markets30d)
                .setConcentrationMarket30d(concentration)
                .setFollowableRate30d(followableRate)
                .setPnl30d(new BigDecimal("160.00"));
    }

    private List<SmartWalletOrderAnalysisEntity> datedOrders(int count,
                                                             String exitStyle,
                                                             int holdMinutes,
                                                             int gapMinutes,
                                                             int startDaysAgo) {
        List<SmartWalletOrderAnalysisEntity> rows = new java.util.ArrayList<>();
        long start = System.currentTimeMillis() - startDaysAgo * 24L * 3600_000L;
        for (int i = 0; i < count; i++) {
            long openedAt = start + i * gapMinutes * 60_000L;
            boolean win = !"SETTLED_LOSS".equals(exitStyle) && !"EARLY_STOP".equals(exitStyle);
            rows.add(new SmartWalletOrderAnalysisEntity()
                    .setWalletAddress("0xcase")
                    .setSource("POLYMARKET")
                    .setMarketId("market-" + i)
                    .setOutcome("YES")
                    .setOpenedAt(new Date(openedAt))
                    .setClosedAt(new Date(openedAt + holdMinutes * 60_000L))
                    .setMarketEndAt(openedAt + (holdMinutes + ("SETTLED_WIN".equals(exitStyle) ? 0 : 240)) * 60_000L)
                    .setInvestedUsdc(new BigDecimal("100"))
                    .setRealizedPnlUsdc(win ? new BigDecimal("10") : new BigDecimal("-10"))
                    .setRoiRate(win ? new BigDecimal("0.10") : new BigDecimal("-0.10"))
                    .setHoldMinutes(new BigDecimal(holdMinutes))
                    .setExitStyle(exitStyle)
                    .setIsWin(win));
        }
        return rows;
    }

    private List<SmartWalletOrderAnalysisEntity> mixedRiskOrders() {
        List<SmartWalletOrderAnalysisEntity> rows = new java.util.ArrayList<>();
        rows.addAll(datedOrders(8, "EARLY_STOP", 90, 180, 2));
        rows.addAll(datedOrders(4, "SETTLED_WIN", 240, 180, 1));
        return rows;
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
