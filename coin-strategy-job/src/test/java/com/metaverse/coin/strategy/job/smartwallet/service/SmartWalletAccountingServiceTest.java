package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletDailyReportMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletAccountingServiceTest {

    @Mock
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletFillMapper fillMapper;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletCashLedgerMapper cashLedgerMapper;
    @Mock
    private SmartWalletDailyReportMapper dailyReportMapper;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private ObjectProvider<SmartWalletFollowTaskService> followTaskServiceProvider;

    private SmartWalletAccountingService service;

    @BeforeEach
    void setUp() {
        SmartWalletProperties properties = new SmartWalletProperties();
        service = new SmartWalletAccountingService(
                orderMapper,
                fillMapper,
                positionMapper,
                cashLedgerMapper,
                dailyReportMapper,
                tradeEventMapper,
                followTaskMapper,
                marketMetadataService,
                new SmartWalletTimeFormatter(),
                properties,
                followTaskServiceProvider
        );
    }

    @Test
    void shouldMarkExpiredPaperOrdersAsOraclePendingInsteadOfResolvingEarly() {
        long past = System.currentTimeMillis() - 60_000L;
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(11L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-1")
                .setQuestion("Will test market resolve yes?")
                .setOutcome("YES")
                .setMarketEndAt(past)
                .setQuantity(new BigDecimal("10"))
                .setCostBasisUsdc(new BigDecimal("4.50"))
                .setLastMarkPrice(new BigDecimal("0.45"))
                .setStatus("OPEN")
                .setLastFilledAt(new Date(past));
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(101L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-1")
                .setOutcome("YES")
                .setStatus("FILLED");
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-1")
                .marketId("market-1")
                .question(position.getQuestion())
                .endAt(past)
                .closed(false)
                .acceptingOrders(false)
                .resolvedOutcome(null)
                .outcomes(List.of("YES", "NO"))
                .outcomePrices(List.of("0.62", "0.38"))
                .outcomeTokenIds(Map.of("YES", "1", "NO", "2"))
                .build();

        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(marketMetadataService.getBestEffortByConditionId(eq("market-1"), anyBoolean(), eq(position.getQuestion()), anyList()))
                .thenReturn(metadata);

        SettlementSyncSummary summary = service.syncSettlements(10, 10, false);

        assertEquals(0, summary.getResolvedPositions());
        assertEquals(1, summary.getUnresolvedMarkets());
        assertEquals(1, summary.getOraclePendingMarkets());
        assertEquals(1, summary.getRetryCandidates().size());
        assertEquals("ORACLE_PENDING", summary.getRetryCandidates().get(0).getRetryType());
        assertTrue(summary.getNotes().get(0).contains("waiting for Polymarket/UMA oracle resolution"));

        ArgumentCaptor<SmartWalletOrderEntity> orderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals("SETTLEMENT_PENDING_ORACLE", orderCaptor.getValue().getStatus());
        verify(positionMapper, never()).updateById(any(SmartWalletPositionEntity.class));
    }

    @Test
    void shouldSettlePaperOrdersOnlyAfterResolvedOutcomeIsAvailable() {
        long past = System.currentTimeMillis() - 60_000L;
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(12L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-2")
                .setQuestion("Will resolved market settle yes?")
                .setOutcome("YES")
                .setMarketEndAt(past)
                .setQuantity(new BigDecimal("10"))
                .setCostBasisUsdc(new BigDecimal("4.00"))
                .setOriginalCostBasisUsdc(new BigDecimal("4.00"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                .setLastMarkPrice(new BigDecimal("0.40"))
                .setStatus("OPEN")
                .setOpenedAt(new Date(past))
                .setLastFilledAt(new Date(past));
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(102L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-2")
                .setOutcome("YES")
                .setStatus("SETTLEMENT_PENDING_ORACLE")
                .setPlacedAt(new Date(past))
                .setNotionalUsdc(new BigDecimal("4.00"));
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-2")
                .marketId("market-2")
                .question(position.getQuestion())
                .endAt(past)
                .closed(true)
                .acceptingOrders(false)
                .resolvedOutcome("YES")
                .outcomes(List.of("YES", "NO"))
                .outcomePrices(List.of("1.0", "0.0"))
                .outcomeTokenIds(Map.of("YES", "1", "NO", "2"))
                .build();

        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(fillMapper.selectList(any())).thenReturn(List.of());
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectOne(any())).thenReturn(null);
        when(marketMetadataService.getBestEffortByConditionId(eq("market-2"), anyBoolean(), eq(position.getQuestion()), anyList()))
                .thenReturn(metadata);

        SettlementSyncSummary summary = service.syncSettlements(10, 10, false);

        assertEquals(1, summary.getResolvedPositions());
        assertEquals(0, summary.getOraclePendingMarkets());

        ArgumentCaptor<SmartWalletOrderEntity> orderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper, atLeastOnce()).updateById(orderCaptor.capture());
        assertTrue(orderCaptor.getAllValues().stream()
                .anyMatch(updated -> "SETTLED_WIN".equals(updated.getStatus())));

        ArgumentCaptor<SmartWalletPositionEntity> positionCaptor = ArgumentCaptor.forClass(SmartWalletPositionEntity.class);
        verify(positionMapper, atLeastOnce()).updateById(positionCaptor.capture());
        assertTrue(positionCaptor.getAllValues().stream()
                .anyMatch(updated -> "RESOLVED_UNCLAIMED".equals(updated.getStatus())));
        verify(cashLedgerMapper, atLeastOnce()).insert(any());
    }
}
