package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEffectLabelMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEffectLabelEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ConsensusSignalView;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeEvScoringServiceTest {

    @Mock
    private SmartWalletTradeEffectLabelMapper tradeEffectLabelMapper;
    @Mock
    private MarketSnapshotCollectorService marketSnapshotCollectorService;

    @Test
    void shouldPreferRealizedSlippageWhenAvailable() {
        SmartWalletProperties properties = new SmartWalletProperties();
        TradeProfitabilityScoringService service = new TradeProfitabilityScoringService(tradeEffectLabelMapper, marketSnapshotCollectorService, properties);
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(List.of(
                label("3.00000000", "0.50000000", "1.25000000", "10")
        ));
        when(marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(any(), any(), any()))
                .thenReturn(snapshot("35", "50", "120", "0.04", "100"));

        TradeProfitabilityScoringService.ProfitabilityEstimate estimate =
                service.estimate(profile(), event(), consensus(), new BigDecimal("10"));

        assertEquals(1, estimate.getEstimateSampleSize());
        assertEquals(new BigDecimal("0.50000000"), estimate.getEstimatedFeeUsdc());
        assertEquals(new BigDecimal("1.25000000"), estimate.getEstimatedSlippageUsdc());
        assertTrue(estimate.getEstimatedNetProfitabilityUsdc().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(estimate.getFollowProbability().compareTo(new BigDecimal("0.70")) > 0);
        assertTrue(estimate.getCalibratedFollowProbability().compareTo(estimate.getFollowProbability()) > 0);
        assertTrue(estimate.getSlippageSpikeProbability().compareTo(new BigDecimal("0.50")) > 0);
    }

    @Test
    void shouldHonorConfiguredBaselineWeights() {
        SmartWalletProperties properties = new SmartWalletProperties();
        properties.getBaselineModel().getFollowProbability().setWalletScoreWeight(new BigDecimal("3.00"));
        properties.getBaselineModel().getFollowProbability().setConsensusScoreWeight(BigDecimal.ZERO);
        TradeProfitabilityScoringService service =
                new TradeProfitabilityScoringService(tradeEffectLabelMapper, marketSnapshotCollectorService, properties);
        when(tradeEffectLabelMapper.selectList(any())).thenReturn(List.of());
        when(marketSnapshotCollectorService.findLatestSnapshotAtOrBefore(any(), any(), any()))
                .thenReturn(snapshot("10", "50", "120", "0.04", "100"));

        TradeProfitabilityScoringService.ProfitabilityEstimate highScore =
                service.estimate(profile(), event(), consensus(), new BigDecimal("10"));
        TradeProfitabilityScoringService.ProfitabilityEstimate lowScore = service.estimate(
                new SmartWalletProfileEntity().setWalletScore(new BigDecimal("0.20")),
                event(),
                consensus(),
                new BigDecimal("10")
        );

        assertTrue(highScore.getFollowProbability().compareTo(lowScore.getFollowProbability()) > 0);
    }

    private SmartWalletTradeEventEntity event() {
        return new SmartWalletTradeEventEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.48"))
                .setSizeUsdc(new BigDecimal("10"))
                .setOpenedAt(new Date(10_000_000L));
    }

    private SmartWalletProfileEntity profile() {
        return new SmartWalletProfileEntity()
                .setWalletScore(new BigDecimal("0.90"));
    }

    private ConsensusSignalView consensus() {
        return ConsensusSignalView.builder()
                .distinctWallets(3)
                .consensusScore(new BigDecimal("0.85"))
                .build();
    }

    private SmartWalletTradeEffectLabelEntity label(String netPnlRule,
                                                    String estimatedFeeUsdc,
                                                    String realizedSlippageUsdc,
                                                    String paperStakeUsdc) {
        SmartWalletTradeEffectLabelEntity label = new SmartWalletTradeEffectLabelEntity()
                .setEventAt(new Date(1_000L))
                .setNetPnlRule(new BigDecimal(netPnlRule))
                .setEstimatedFeeUsdc(new BigDecimal(estimatedFeeUsdc))
                .setEstimatedSlippageUsdc(new BigDecimal("2.30000000"))
                .setPaperStakeUsdc(new BigDecimal(paperStakeUsdc));
        if (realizedSlippageUsdc != null) {
            label.setRealizedSlippageUsdc(new BigDecimal(realizedSlippageUsdc));
        }
        return label;
    }

    private SmartWalletMarketSnapshotEntity snapshot(String spreadBps,
                                                     String askDepth3c,
                                                     String askDepth1c,
                                                     String realizedVol30s,
                                                     String midPrice) {
        return new SmartWalletMarketSnapshotEntity()
                .setSpreadBps(new BigDecimal(spreadBps))
                .setAskDepth3c(new BigDecimal(askDepth3c))
                .setAskDepth1c(new BigDecimal(askDepth1c))
                .setRealizedVol30s(new BigDecimal(realizedVol30s))
                .setMidPrice(new BigDecimal(midPrice));
    }
}
