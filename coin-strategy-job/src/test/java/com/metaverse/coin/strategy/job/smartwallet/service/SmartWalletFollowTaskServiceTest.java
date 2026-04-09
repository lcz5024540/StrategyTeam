package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletProfileMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFollowTaskEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SignalRiskDecision;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowTaskView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletFollowTaskServiceTest {

    @Mock
    private SmartWalletProperties properties;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletProfileMapper profileMapper;
    @Mock
    private SmartWalletProfileStoreService profileStoreService;
    @Mock
    private PolymarketExecutionGateway executionGateway;
    @Mock
    private SmartWalletSignalRiskService signalRiskService;
    @Mock
    private PolymarketConsensusSignalService consensusSignalService;
    @Mock
    private SmartWalletAccountingService accountingService;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private SmartWalletTimeFormatter timeFormatter;

    @InjectMocks
    private SmartWalletFollowTaskService service;

    @Test
    void shouldSortExecutableTasksByEvBeforeStake() {
        SmartWalletFollowTaskEntity lowerEvHigherStake = followTask(1L, new BigDecimal("20.00"));
        SmartWalletFollowTaskEntity higherEvLowerStake = followTask(2L, new BigDecimal("10.00"));
        SmartWalletTradeEventEntity event = tradeEvent();
        SmartWalletProfileEntity profile = profile();

        when(followTaskMapper.selectList(any())).thenReturn(List.of(lowerEvHigherStake, higherEvLowerStake));
        when(followTaskMapper.selectById(1L)).thenReturn(lowerEvHigherStake);
        when(followTaskMapper.selectById(2L)).thenReturn(higherEvLowerStake);
        when(tradeEventMapper.selectById(anyLong())).thenReturn(event);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(1L))).thenReturn(acceptedDecision("1.50", "20.00", 12));
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(2L))).thenReturn(acceptedDecision("3.00", "10.00", 25));
        when(consensusSignalService.listRecentConsensusSignals()).thenReturn(List.of());
        when(timeFormatter.formatEpochMillisToBjt(any())).thenReturn("-");

        List<SmartWalletFollowTaskView> result = service.listExecutableTasks(10);

        assertEquals(List.of(2L, 1L), result.stream().map(SmartWalletFollowTaskView::getId).toList());
    }

    @Test
    void shouldSortOpportunityTasksByLowerBlockersThenHigherEv() {
        SmartWalletFollowTaskEntity lowBlockerLowEv = followTask(11L, new BigDecimal("15.00"));
        SmartWalletFollowTaskEntity lowBlockerHighEv = followTask(12L, new BigDecimal("8.00"));
        SmartWalletTradeEventEntity event = tradeEvent();
        SmartWalletProfileEntity profile = profile();

        when(followTaskMapper.selectList(any())).thenReturn(List.of(lowBlockerLowEv, lowBlockerHighEv));
        when(followTaskMapper.selectById(11L)).thenReturn(lowBlockerLowEv);
        when(followTaskMapper.selectById(12L)).thenReturn(lowBlockerHighEv);
        when(tradeEventMapper.selectById(anyLong())).thenReturn(event);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(11L))).thenReturn(blockedDecision("0.50", "15.00", List.of("Consensus score is below threshold."), 18));
        when(signalRiskService.evaluate(eq(profile), eq(event), isNull(), eq(12L))).thenReturn(blockedDecision("2.20", "8.00", List.of("Consensus wallet count is below threshold."), 30));
        when(consensusSignalService.listRecentConsensusSignals()).thenReturn(List.of());
        when(timeFormatter.formatEpochMillisToBjt(any())).thenReturn("-");

        List<SmartWalletFollowTaskView> result = service.listOpportunityTasks(10, 3, false);

        assertEquals(List.of(12L, 11L), result.stream().map(SmartWalletFollowTaskView::getId).toList());
    }

    private SmartWalletFollowTaskEntity followTask(Long id, BigDecimal stake) {
        return new SmartWalletFollowTaskEntity()
                .setId(id)
                .setSourceEventId(1000L + id)
                .setWalletAddress("0xabc")
                .setMarketId("market-1")
                .setQuestion("Will this market resolve yes?")
                .setOutcome("YES")
                .setMarketEndAt(System.currentTimeMillis() + 3600_000L)
                .setSide("BUY")
                .setTriggerPrice(new BigDecimal("0.61"))
                .setTriggerSizeUsdc(new BigDecimal("100.00"))
                .setRecommendedStakeUsdc(stake)
                .setStatus("PENDING")
                .setScheduledAt(new Date());
    }

    private SmartWalletTradeEventEntity tradeEvent() {
        return new SmartWalletTradeEventEntity()
                .setId(1L)
                .setWalletAddress("0xabc")
                .setMarketId("market-1")
                .setQuestion("Will this market resolve yes?")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.61"))
                .setSizeUsdc(new BigDecimal("100.00"))
                .setOpenedAt(new Date());
    }

    private SmartWalletProfileEntity profile() {
        return new SmartWalletProfileEntity()
                .setWalletAddress("0xabc")
                .setSource("POLYMARKET")
                .setQualified(Boolean.TRUE)
                .setWatchEnabled(Boolean.TRUE);
    }

    private SignalRiskDecision acceptedDecision(String ev, String stake, Integer sampleSize) {
        return SignalRiskDecision.builder()
                .accepted(true)
                .recommendedStakeUsdc(new BigDecimal(stake))
                .expectedNetEvUsdc(new BigDecimal(ev))
                .evSampleSize(sampleSize)
                .reasons(List.of("Signal passed watchlist, freshness, size and price checks."))
                .build();
    }

    private SignalRiskDecision blockedDecision(String ev, String stake, List<String> reasons, Integer sampleSize) {
        return SignalRiskDecision.builder()
                .accepted(false)
                .recommendedStakeUsdc(new BigDecimal(stake))
                .expectedNetEvUsdc(new BigDecimal(ev))
                .evSampleSize(sampleSize)
                .reasons(reasons)
                .build();
    }
}
