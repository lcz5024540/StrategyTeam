package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletDailyReportMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFillMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletCashLedgerEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletDailyReportEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletFillEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.SettlementSyncSummary;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletFollowAddressDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletDailyReportView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletOrderView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPerformanceView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlTimelinePoint;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPnlSummaryView;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
    @Mock
    private SmartWalletMarketInfoService marketInfoService;

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
                followTaskServiceProvider,
                marketInfoService
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

        ArgumentCaptor<SmartWalletPositionEntity> positionCaptor = ArgumentCaptor.forClass(SmartWalletPositionEntity.class);
        verify(positionMapper, atLeastOnce()).updateById(positionCaptor.capture());
        assertTrue(positionCaptor.getAllValues().stream()
                .anyMatch(updated -> "SETTLEMENT_PENDING_ORACLE".equals(updated.getStatus())));
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

    @Test
    void shouldSettleUsingTokenMappingWhenStoredOutcomeDiffers() {
        long past = System.currentTimeMillis() - 60_000L;
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(14L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-token-map")
                .setTokenId("token-yes")
                .setQuestion("Will token mapping repair settlement?")
                .setOutcome("NO")
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
                .setId(104L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-token-map")
                .setTokenId("token-yes")
                .setOutcome("NO")
                .setStatus("SETTLEMENT_PENDING_ORACLE")
                .setPlacedAt(new Date(past))
                .setNotionalUsdc(new BigDecimal("4.00"));
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-token-map")
                .marketId("market-token-map")
                .question(position.getQuestion())
                .endAt(past)
                .closed(true)
                .acceptingOrders(false)
                .resolvedOutcome("YES")
                .winningTokenId("token-yes")
                .outcomes(List.of("YES", "NO"))
                .outcomePrices(List.of("1.0", "0.0"))
                .outcomeTokenIds(Map.of("YES", "token-yes", "NO", "token-no"))
                .build();

        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(fillMapper.selectList(any())).thenReturn(List.of());
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectOne(any())).thenReturn(null);
        when(marketMetadataService.getBestEffortByConditionId(eq("market-token-map"), anyBoolean(), eq(position.getQuestion()), anyList()))
                .thenReturn(metadata);
        when(marketMetadataService.resolveOutcomeByTokenId(eq(metadata), eq("token-yes"), eq("NO"))).thenReturn("YES");

        SettlementSyncSummary summary = service.syncSettlements(10, 10, false);

        assertEquals(1, summary.getResolvedPositions());

        ArgumentCaptor<SmartWalletOrderEntity> orderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper, atLeastOnce()).updateById(orderCaptor.capture());
        assertTrue(orderCaptor.getAllValues().stream()
                .anyMatch(updated -> "SETTLED_WIN".equals(updated.getStatus()) && "YES".equals(updated.getOutcome())));

        ArgumentCaptor<SmartWalletPositionEntity> positionCaptor = ArgumentCaptor.forClass(SmartWalletPositionEntity.class);
        verify(positionMapper, atLeastOnce()).updateById(positionCaptor.capture());
        assertTrue(positionCaptor.getAllValues().stream()
                .anyMatch(updated -> "RESOLVED_UNCLAIMED".equals(updated.getStatus()) && "YES".equals(updated.getOutcome())));
    }

    @Test
    void shouldExposeSettledEntryOrderRealizedPnlFromPosition() {
        Date filledAt = new Date();
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(167L)
                .setFollowTaskId(15182L)
                .setPositionId(125L)
                .setWalletAddress("wallet")
                .setExecutionMode("PAPER")
                .setExecutionAddress("paper-book")
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setMarketId("market-settled-entry")
                        .setOutcome("CALVIN HEMERY"))
                .setSide("BUY")
                .setOrderType("GTC")
                .setStatus("SETTLED_WIN")
                .setPrice(new BigDecimal("0.81000000"))
                .setSize(new BigDecimal("101.37037037"))
                .setNotionalUsdc(new BigDecimal("82.11000000"))
                .setFeeUsdc(new BigDecimal("0.12316500"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setPlacedAt(filledAt)
                .setFilledAt(filledAt);
        SmartWalletFillEntity fill = new SmartWalletFillEntity()
                .setId(501L)
                .setOrderId(167L)
                .setPositionId(125L)
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setMarketId("market-settled-entry")
                        .setOutcome("CALVIN HEMERY"))
                .setSide("BUY")
                .setNotionalUsdc(new BigDecimal("82.11000000"))
                .setFeeUsdc(new BigDecimal("0.12316500"))
                .setFilledAt(filledAt);
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(125L)
                .setExecutionAddress("paper-book")
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setMarketId("market-settled-entry")
                        .setOutcome("CALVIN HEMERY"))
                .setStatus("CLAIMED")
                .setRealizedPnlUsdc(new BigDecimal("19.13720537"));

        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(fillMapper.selectList(any())).thenReturn(List.of(fill));
        when(positionMapper.selectById(125L)).thenReturn(position);

        List<SmartWalletOrderView> orders = service.listOrders(10, "PAPER");

        assertEquals(1, orders.size());
        assertEquals("SETTLED_WIN", orders.get(0).getStatus());
        assertEquals(new BigDecimal("19.13720537"), orders.get(0).getRealizedPnlUsdc());
    }

    @Test
    void shouldExposePairedExitRealizedPnlOnEntryOrderView() {
        Date entryAt = new Date(System.currentTimeMillis() - 60_000L);
        Date exitAt = new Date();
        SmartWalletOrderEntity entryOrder = new SmartWalletOrderEntity()
                .setId(170L)
                .setFollowTaskId(15780L)
                .setPositionId(127L)
                .setWalletAddress("wallet")
                .setExecutionMode("PAPER")
                .setExecutionAddress("paper-book")
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setMarketId("market-early-exit")
                        .setOutcome("J.J. WOLF"))
                .setSide("BUY")
                .setOrderType("GTC")
                .setStatus("FILLED")
                .setPrice(new BigDecimal("0.20000000"))
                .setSize(new BigDecimal("750.00000000"))
                .setNotionalUsdc(new BigDecimal("150.00000000"))
                .setFeeUsdc(new BigDecimal("0.22500000"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setPlacedAt(entryAt)
                .setFilledAt(entryAt);
        SmartWalletFillEntity entryFill = new SmartWalletFillEntity()
                .setId(601L)
                .setOrderId(170L)
                .setPositionId(127L)
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setMarketId("market-early-exit")
                        .setOutcome("J.J. WOLF"))
                .setSide("BUY")
                .setNotionalUsdc(new BigDecimal("150.00000000"))
                .setFeeUsdc(new BigDecimal("0.22500000"))
                .setFilledAt(entryAt);
        SmartWalletFillEntity exitFill = new SmartWalletFillEntity()
                .setId(602L)
                .setOrderId(172L)
                .setPositionId(127L)
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setMarketId("market-early-exit")
                        .setOutcome("J.J. WOLF"))
                .setSide("SELL")
                .setNotionalUsdc(new BigDecimal("307.50000000"))
                .setFeeUsdc(BigDecimal.ZERO)
                .setFilledAt(exitAt);
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(127L)
                .setExecutionAddress("paper-book")
                .setMarketInfo(new SmartWalletMarketInfoEntity()
                        .setMarketId("market-early-exit")
                        .setOutcome("J.J. WOLF"))
                .setStatus("CLOSED")
                .setRealizedPnlUsdc(new BigDecimal("157.27500000"));

        when(orderMapper.selectList(any())).thenReturn(List.of(entryOrder));
        when(fillMapper.selectList(any())).thenReturn(List.of(entryFill, exitFill));
        when(positionMapper.selectById(127L)).thenReturn(position);

        List<SmartWalletOrderView> orders = service.listOrders(10, "PAPER");

        assertEquals(1, orders.size());
        assertEquals("SETTLED_WIN", orders.get(0).getStatus());
        assertEquals(new BigDecimal("157.27500000"), orders.get(0).getRealizedPnlUsdc());
    }

    @Test
    void shouldApplyOnlyDeltaWhenOrderReceivesAdditionalPartialFill() {
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(103L)
                .setFollowTaskId(1003L)
                .setWalletAddress("wallet-1")
                .setExecutionAddress("live-wallet")
                .setMarketId("market-3")
                .setTokenId("token-3")
                .setQuestion("Will partial fill continue?")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.50"))
                .setMarketEndAt(System.currentTimeMillis() + 3600_000L)
                .setPlacedAt(new Date());
        SmartWalletFillEntity existingFill = new SmartWalletFillEntity()
                .setOrderId(103L)
                .setSize(new BigDecimal("5.00000000"))
                .setNotionalUsdc(new BigDecimal("2.50000000"))
                .setFeeUsdc(BigDecimal.ZERO)
                .setFilledAt(new Date());
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(203L)
                .setExecutionAddress("live-wallet")
                .setMarketId("market-3")
                .setOutcome("YES")
                .setQuestion("Will partial fill continue?")
                .setQuantity(new BigDecimal("5.00000000"))
                .setAverageEntryPrice(new BigDecimal("0.50"))
                .setCostBasisUsdc(new BigDecimal("2.50000000"))
                .setOriginalCostBasisUsdc(new BigDecimal("2.50000000"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                .setLastMarkPrice(new BigDecimal("0.50"))
                .setStatus("OPEN")
                .setOpenedAt(new Date())
                .setLastFilledAt(new Date());

        when(fillMapper.selectList(any())).thenReturn(List.of(existingFill), List.of());
        when(positionMapper.selectOne(any())).thenReturn(position);
        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectOne(any())).thenReturn(null);
        when(tradeEventMapper.selectOne(any())).thenReturn(null);

        service.processOrderFill(order, new BigDecimal("8.00000000"), new BigDecimal("0.50"), BigDecimal.ZERO);

        ArgumentCaptor<SmartWalletFillEntity> fillCaptor = ArgumentCaptor.forClass(SmartWalletFillEntity.class);
        verify(fillMapper).insert(fillCaptor.capture());
        assertEquals(new BigDecimal("3.00000000"), fillCaptor.getValue().getSize());
        assertEquals(new BigDecimal("1.50000000"), fillCaptor.getValue().getNotionalUsdc());
    }

    @Test
    void shouldExitPaperPositionViaSellOrderAndRealizePnl() {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(301L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-exit")
                .setTokenId("token-exit")
                .setQuestion("Can paper position exit early?")
                .setOutcome("YES")
                .setMarketEndAt(System.currentTimeMillis() + 3_600_000L)
                .setQuantity(new BigDecimal("10.00000000"))
                .setAverageEntryPrice(new BigDecimal("0.40"))
                .setCostBasisUsdc(new BigDecimal("4.00000000"))
                .setOriginalCostBasisUsdc(new BigDecimal("4.00000000"))
                .setRealizedPnlUsdc(BigDecimal.ZERO.setScale(8))
                .setUnrealizedPnlUsdc(new BigDecimal("1.00000000"))
                .setLastMarkPrice(new BigDecimal("0.50"))
                .setStatus("OPEN")
                .setOpenedAt(new Date())
                .setLastFilledAt(new Date());
        SmartWalletFillEntity latestFill = new SmartWalletFillEntity()
                .setId(401L)
                .setOrderId(201L)
                .setPositionId(301L)
                .setFollowTaskId(101L)
                .setWalletAddress("source-wallet")
                .setExecutionAddress("paper-book")
                .setMarketId("market-exit")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.40"))
                .setSize(new BigDecimal("10.00000000"))
                .setNotionalUsdc(new BigDecimal("4.00000000"))
                .setFilledAt(new Date());
        SmartWalletPositionExitRequest request = new SmartWalletPositionExitRequest();
        request.setPrice(new BigDecimal("0.70"));
        request.setReason("MANUAL_TAKE_PROFIT");

        when(positionMapper.selectById(301L)).thenReturn(position, position);
        when(positionMapper.selectOne(any())).thenReturn(position);
        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(fillMapper.selectList(any())).thenReturn(List.of(latestFill), List.of(latestFill));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectOne(any())).thenReturn(null);
        when(tradeEventMapper.selectOne(any())).thenReturn(null);

        SmartWalletPositionExitResultView result = service.exitPosition(301L, request);

        assertEquals("CLOSED", result.getCurrentStatus());
        assertEquals(new BigDecimal("10.00000000"), result.getExitQuantity());
        assertEquals(new BigDecimal("7.00000000"), result.getExitProceedsUsdc());
        assertEquals(new BigDecimal("3.00000000"), result.getRealizedPnlDeltaUsdc());
        assertEquals(BigDecimal.ZERO.setScale(8), result.getRemainingQuantity());

        ArgumentCaptor<SmartWalletOrderEntity> orderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals("SELL", orderCaptor.getValue().getSide());
        assertEquals("EXIT", orderCaptor.getValue().getOrderType());
        assertEquals(301L, orderCaptor.getValue().getPositionId());

        ArgumentCaptor<SmartWalletFillEntity> fillCaptor = ArgumentCaptor.forClass(SmartWalletFillEntity.class);
        verify(fillMapper).insert(fillCaptor.capture());
        assertEquals("SELL", fillCaptor.getValue().getSide());
        assertEquals(new BigDecimal("10.00000000"), fillCaptor.getValue().getSize());
        assertEquals(new BigDecimal("7.00000000"), fillCaptor.getValue().getNotionalUsdc());
        assertEquals(301L, fillCaptor.getValue().getPositionId());
    }

    @Test
    void shouldExcludeGasFeeFromRealizedPnlButKeepTotalFeeDisplayOnExit() {
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(311L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-exit-fee-split")
                .setTokenId("token-exit-fee-split")
                .setQuestion("Can split fees stay out of realized pnl?")
                .setOutcome("YES")
                .setMarketEndAt(System.currentTimeMillis() + 3_600_000L)
                .setQuantity(new BigDecimal("10.00000000"))
                .setAverageEntryPrice(new BigDecimal("0.40"))
                .setCostBasisUsdc(new BigDecimal("4.00000000"))
                .setOriginalCostBasisUsdc(new BigDecimal("4.00000000"))
                .setRealizedPnlUsdc(BigDecimal.ZERO.setScale(8))
                .setUnrealizedPnlUsdc(new BigDecimal("1.00000000"))
                .setLastMarkPrice(new BigDecimal("0.50"))
                .setStatus("OPEN")
                .setOpenedAt(new Date())
                .setLastFilledAt(new Date());
        SmartWalletFillEntity latestFill = new SmartWalletFillEntity()
                .setId(411L)
                .setOrderId(211L)
                .setPositionId(311L)
                .setFollowTaskId(111L)
                .setWalletAddress("source-wallet")
                .setExecutionAddress("paper-book")
                .setMarketId("market-exit-fee-split")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.40"))
                .setSize(new BigDecimal("10.00000000"))
                .setNotionalUsdc(new BigDecimal("4.00000000"))
                .setFilledAt(new Date());
        SmartWalletPositionExitRequest request = new SmartWalletPositionExitRequest();
        request.setPrice(new BigDecimal("0.70"));
        request.setFeeUsdc(new BigDecimal("0.50000000"));
        request.setTradingFeeUsdc(new BigDecimal("0.20000000"));
        request.setGasFeeUsdc(new BigDecimal("0.30000000"));
        request.setFeePol(new BigDecimal("1.25000000"));
        request.setReason("MANUAL_TAKE_PROFIT");

        when(positionMapper.selectById(311L)).thenReturn(position, position);
        when(positionMapper.selectOne(any())).thenReturn(position);
        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(fillMapper.selectList(any())).thenReturn(List.of(latestFill), List.of(latestFill));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectOne(any())).thenReturn(null);
        when(tradeEventMapper.selectOne(any())).thenReturn(null);

        SmartWalletPositionExitResultView result = service.exitPosition(311L, request);

        assertEquals(new BigDecimal("2.80000000"), result.getRealizedPnlDeltaUsdc());

        ArgumentCaptor<SmartWalletOrderEntity> orderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertEquals(new BigDecimal("0.50000000"), orderCaptor.getValue().getFeeUsdc());
        assertEquals(new BigDecimal("0.20000000"), orderCaptor.getValue().getTradingFeeUsdc());
        assertEquals(new BigDecimal("0.30000000"), orderCaptor.getValue().getGasFeeUsdc());
        assertEquals(new BigDecimal("1.25000000"), orderCaptor.getValue().getFeePol());

        ArgumentCaptor<SmartWalletFillEntity> fillCaptor = ArgumentCaptor.forClass(SmartWalletFillEntity.class);
        verify(fillMapper).insert(fillCaptor.capture());
        assertEquals(new BigDecimal("0.50000000"), fillCaptor.getValue().getFeeUsdc());
        assertEquals(new BigDecimal("0.20000000"), fillCaptor.getValue().getTradingFeeUsdc());
        assertEquals(new BigDecimal("0.30000000"), fillCaptor.getValue().getGasFeeUsdc());
        assertEquals(new BigDecimal("1.25000000"), fillCaptor.getValue().getFeePol());
    }

    @Test
    void shouldCreateNewPositionRoundWhenPreviousRoundIsAlreadyClosed() {
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(601L)
                .setFollowTaskId(1601L)
                .setWalletAddress("wallet-round")
                .setExecutionAddress("paper-book")
                .setMarketId("market-round")
                .setTokenId("token-round")
                .setQuestion("Will a closed round reopen as a new position?")
                .setOutcome("YES")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.40"))
                .setMarketEndAt(System.currentTimeMillis() + 3600_000L)
                .setPlacedAt(new Date());

        when(fillMapper.selectList(any())).thenReturn(List.of(), List.of(), List.of());
        when(positionMapper.selectList(any())).thenReturn(List.of(), List.of(), List.of());
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectOne(any())).thenReturn(null);
        when(tradeEventMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SmartWalletPositionEntity inserted = invocation.getArgument(0);
            inserted.setId(901L);
            return 1;
        }).when(positionMapper).insert(any(SmartWalletPositionEntity.class));

        service.processOrderFill(order, new BigDecimal("5.00000000"), new BigDecimal("0.40"), BigDecimal.ZERO);

        ArgumentCaptor<SmartWalletPositionEntity> positionCaptor = ArgumentCaptor.forClass(SmartWalletPositionEntity.class);
        verify(positionMapper).insert(positionCaptor.capture());
        assertEquals("market-round", positionCaptor.getValue().getMarketId());
        assertEquals("YES", positionCaptor.getValue().getOutcome());
        assertEquals("OPEN", positionCaptor.getValue().getStatus());

        ArgumentCaptor<SmartWalletOrderEntity> updatedOrderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper, atLeastOnce()).updateById(updatedOrderCaptor.capture());
        assertTrue(updatedOrderCaptor.getAllValues().stream()
                .anyMatch(updated -> Long.valueOf(901L).equals(updated.getPositionId())));

        ArgumentCaptor<SmartWalletFillEntity> updatedFillCaptor = ArgumentCaptor.forClass(SmartWalletFillEntity.class);
        verify(fillMapper, atLeastOnce()).updateById(updatedFillCaptor.capture());
        assertTrue(updatedFillCaptor.getAllValues().stream()
                .anyMatch(updated -> Long.valueOf(901L).equals(updated.getPositionId())));
    }

    @Test
    void shouldReuseActivePositionByTokenIdBeforeLegacyOutcomeFallback() {
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(701L)
                .setFollowTaskId(1701L)
                .setWalletAddress("wallet-token")
                .setExecutionAddress("paper-book")
                .setMarketId("market-token")
                .setTokenId("token-yes")
                .setQuestion("Will token id drive position reuse?")
                .setOutcome("NO")
                .setSide("BUY")
                .setPrice(new BigDecimal("0.40"))
                .setMarketEndAt(System.currentTimeMillis() + 3600_000L)
                .setPlacedAt(new Date());
        SmartWalletPositionEntity existingPosition = new SmartWalletPositionEntity()
                .setId(801L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-token")
                .setTokenId("token-yes")
                .setQuestion("Will token id drive position reuse?")
                .setOutcome("YES")
                .setQuantity(new BigDecimal("2.00000000"))
                .setAverageEntryPrice(new BigDecimal("0.40"))
                .setCostBasisUsdc(new BigDecimal("0.80000000"))
                .setOriginalCostBasisUsdc(new BigDecimal("0.80000000"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                .setLastMarkPrice(new BigDecimal("0.40"))
                .setStatus("OPEN")
                .setOpenedAt(new Date())
                .setLastFilledAt(new Date());

        when(fillMapper.selectList(any())).thenReturn(List.of(), List.of());
        when(positionMapper.selectList(any())).thenReturn(List.of(existingPosition), List.of(existingPosition));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectOne(any())).thenReturn(null);
        when(tradeEventMapper.selectOne(any())).thenReturn(null);

        service.processOrderFill(order, new BigDecimal("3.00000000"), new BigDecimal("0.40"), BigDecimal.ZERO);

        verify(positionMapper, never()).insert(any());

        ArgumentCaptor<SmartWalletOrderEntity> updatedOrderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper, atLeastOnce()).updateById(updatedOrderCaptor.capture());
        assertTrue(updatedOrderCaptor.getAllValues().stream()
                .anyMatch(updated -> Long.valueOf(801L).equals(updated.getPositionId())));
    }

    @Test
    void shouldBackfillMissingTokenAndPositionLinks() {
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(901L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-backfill")
                .setQuestion("Will missing token and position links be backfilled?")
                .setOutcome("YES")
                .setPlacedAt(new Date());
        SmartWalletFillEntity fill = new SmartWalletFillEntity()
                .setId(902L)
                .setOrderId(901L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-backfill")
                .setQuestion("Will missing token and position links be backfilled?")
                .setOutcome("YES")
                .setFilledAt(new Date());
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(903L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-backfill")
                .setQuestion("Will missing token and position links be backfilled?")
                .setOutcome("YES")
                .setTokenId("token-yes")
                .setOpenedAt(new Date(System.currentTimeMillis() - 60_000L))
                .setStatus("OPEN");
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-backfill")
                .marketId("market-backfill")
                .question(position.getQuestion())
                .outcomeTokenIds(Map.of("YES", "token-yes", "NO", "token-no"))
                .build();
        SmartWalletOrderEntity linkedOrder = new SmartWalletOrderEntity()
                .setId(901L)
                .setTokenId("token-yes")
                .setPositionId(903L);

        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(fillMapper.selectList(any())).thenReturn(List.of(), List.of(fill));
        when(positionMapper.selectList(any())).thenReturn(List.of(position), List.of(), List.of(position));
        when(orderMapper.selectById(901L)).thenReturn(linkedOrder);
        when(marketMetadataService.getBestEffortByConditionId(eq("market-backfill"), anyBoolean(), eq(position.getQuestion()), anyList()))
                .thenReturn(metadata);

        Map<String, Object> result = service.backfillMissingTokenAndPositionLinks(10, false);

        assertEquals(2, result.get("totalUpdated"));

        ArgumentCaptor<SmartWalletOrderEntity> orderCaptor = ArgumentCaptor.forClass(SmartWalletOrderEntity.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals("token-yes", orderCaptor.getValue().getTokenId());
        assertEquals(903L, orderCaptor.getValue().getPositionId());

        ArgumentCaptor<SmartWalletFillEntity> fillCaptor = ArgumentCaptor.forClass(SmartWalletFillEntity.class);
        verify(fillMapper).updateById(fillCaptor.capture());
        assertEquals("token-yes", fillCaptor.getValue().getTokenId());
        assertEquals(903L, fillCaptor.getValue().getPositionId());
    }

    @Test
    void shouldExcludeOraclePendingPositionsFromOpenPositionCount() {
        SmartWalletPositionEntity oraclePending = new SmartWalletPositionEntity()
                .setId(501L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-pending")
                .setQuestion("Will this market wait for oracle?")
                .setOutcome("YES")
                .setQuantity(new BigDecimal("10.00000000"))
                .setCostBasisUsdc(new BigDecimal("4.00000000"))
                .setOriginalCostBasisUsdc(new BigDecimal("4.00000000"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setUnrealizedPnlUsdc(new BigDecimal("2.00000000"))
                .setLastMarkPrice(new BigDecimal("0.60"))
                .setMarketEndAt(System.currentTimeMillis() - 60_000L)
                .setStatus("SETTLEMENT_PENDING_ORACLE")
                .setOpenedAt(new Date())
                .setLastFilledAt(new Date());

        when(positionMapper.selectList(any())).thenReturn(List.of(oraclePending));
        when(fillMapper.selectList(any())).thenReturn(List.of());
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(tradeEventMapper.selectOne(any())).thenReturn(null);

        SmartWalletPerformanceView performance = service.performance();

        assertEquals(0, performance.getOpenPositions());
        assertEquals(new BigDecimal("2.00000000"), performance.getPendingSettlementPnlUsdc());
        assertEquals(new BigDecimal("2.00000000"), performance.getCompositePnlUsdc());
    }

    @Test
    void shouldCountOraclePendingCapitalAsStillLocked() {
        SmartWalletPositionEntity openPosition = new SmartWalletPositionEntity()
                .setId(601L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-open")
                .setQuestion("Should open capital remain locked?")
                .setOutcome("YES")
                .setQuantity(new BigDecimal("10.00000000"))
                .setCostBasisUsdc(new BigDecimal("100.00000000"))
                .setStatus("OPEN");
        SmartWalletPositionEntity oraclePending = new SmartWalletPositionEntity()
                .setId(602L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-pending-capital")
                .setQuestion("Should oracle pending capital remain locked?")
                .setOutcome("NO")
                .setQuantity(new BigDecimal("10.00000000"))
                .setCostBasisUsdc(new BigDecimal("50.00000000"))
                .setStatus("SETTLEMENT_PENDING_ORACLE");
        SmartWalletCashLedgerEntity realized = new SmartWalletCashLedgerEntity()
                .setExecutionAddress("paper-book")
                .setEntryType("REALIZED_PNL")
                .setDirection("IN")
                .setAmount(new BigDecimal("30.00000000"));

        when(positionMapper.selectList(any())).thenReturn(List.of(openPosition, oraclePending));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of(realized));

        var capacity = service.capacitySummary();

        assertEquals(1, capacity.getOpenPositionCount());
        assertEquals(new BigDecimal("150.00000000"), capacity.getTotalOpenCostUsdc());
        assertEquals(new BigDecimal("380.00000000"), capacity.getAvailableCapitalUsdc());
    }

    @Test
    void shouldExposeOraclePendingPnlInSummary() {
        SmartWalletPositionEntity oraclePending = new SmartWalletPositionEntity()
                .setId(701L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-summary-pending")
                .setQuestion("Should summary include oracle pending pnl?")
                .setOutcome("YES")
                .setQuantity(new BigDecimal("10.00000000"))
                .setCostBasisUsdc(new BigDecimal("4.00000000"))
                .setOriginalCostBasisUsdc(new BigDecimal("4.00000000"))
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setUnrealizedPnlUsdc(new BigDecimal("2.00000000"))
                .setLastMarkPrice(new BigDecimal("0.60"))
                .setMarketEndAt(System.currentTimeMillis() - 60_000L)
                .setStatus("SETTLEMENT_PENDING_ORACLE")
                .setOpenedAt(new Date())
                .setLastFilledAt(new Date());

        when(positionMapper.selectList(any())).thenReturn(List.of(oraclePending));
        when(fillMapper.selectList(any())).thenReturn(List.of());
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(tradeEventMapper.selectOne(any())).thenReturn(null);

        SmartWalletPnlSummaryView summary = service.summary();

        assertEquals(0, summary.getOpenPositions());
        assertEquals(new BigDecimal("2.00000000"), summary.getPendingSettlementPnlUsdc());
        assertEquals(new BigDecimal("2.00000000"), summary.getCompositePnlUsdc());
    }

    @Test
    void shouldClassifyClosedUnresolvedExpiredMarketAsDataSourceStale() {
        long past = System.currentTimeMillis() - 60_000L;
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(13L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-stale")
                .setQuestion("Will stale metadata be classified?")
                .setOutcome("YES")
                .setMarketEndAt(past)
                .setQuantity(new BigDecimal("10"))
                .setCostBasisUsdc(new BigDecimal("4.50"))
                .setLastMarkPrice(new BigDecimal("0.45"))
                .setStatus("OPEN")
                .setLastFilledAt(new Date(past));
        SmartWalletOrderEntity order = new SmartWalletOrderEntity()
                .setId(103L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-stale")
                .setOutcome("YES")
                .setStatus("FILLED");
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-stale")
                .marketId("market-stale")
                .question(position.getQuestion())
                .endAt(past)
                .closed(true)
                .acceptingOrders(false)
                .resolvedOutcome(null)
                .umaResolutionStatus("resolved")
                .outcomes(List.of("YES", "NO"))
                .outcomePrices(List.of("1.0", "0.0"))
                .build();

        when(positionMapper.selectList(any())).thenReturn(List.of(position));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(marketMetadataService.getBestEffortByConditionId(eq("market-stale"), anyBoolean(), eq(position.getQuestion()), anyList()))
                .thenReturn(metadata);

        SettlementSyncSummary summary = service.syncSettlements(10, 10, false);

        assertEquals("DATA_SOURCE_STALE", summary.getRetryCandidates().get(0).getRetryType());
    }

    @Test
    void shouldBuildDailyFollowAddressReportGroupedBySourceWallet() {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate reportDate = LocalDate.now(zoneId);
        Date withinDay = Date.from(reportDate.atTime(10, 0).atZone(zoneId).toInstant());
        Date closeAt = Date.from(reportDate.atTime(18, 0).atZone(zoneId).toInstant());

        SmartWalletOrderEntity orderA = new SmartWalletOrderEntity()
                .setId(201L)
                .setWalletAddress("0xAAA")
                .setExecutionAddress("paper-book")
                .setMarketId("market-a")
                .setOutcome("YES")
                .setPlacedAt(withinDay);
        SmartWalletOrderEntity orderB = new SmartWalletOrderEntity()
                .setId(202L)
                .setWalletAddress("0xBBB")
                .setExecutionAddress("paper-book")
                .setMarketId("market-b")
                .setOutcome("NO")
                .setPlacedAt(withinDay);

        SmartWalletFillEntity fillA = new SmartWalletFillEntity()
                .setId(301L)
                .setOrderId(201L)
                .setWalletAddress("0xAAA")
                .setExecutionAddress("paper-book")
                .setMarketId("market-a")
                .setOutcome("YES")
                .setNotionalUsdc(new BigDecimal("40.00"))
                .setFeeUsdc(new BigDecimal("0.50"))
                .setFilledAt(withinDay);
        SmartWalletFillEntity fillB = new SmartWalletFillEntity()
                .setId(302L)
                .setOrderId(202L)
                .setWalletAddress("0xBBB")
                .setExecutionAddress("paper-book")
                .setMarketId("market-b")
                .setOutcome("NO")
                .setNotionalUsdc(new BigDecimal("25.00"))
                .setFeeUsdc(new BigDecimal("0.20"))
                .setFilledAt(withinDay);

        SmartWalletPositionEntity closedWin = new SmartWalletPositionEntity()
                .setId(401L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-a")
                .setOutcome("YES")
                .setRealizedPnlUsdc(new BigDecimal("12.30"))
                .setClosedAt(closeAt)
                .setStatus("CLAIMED");
        SmartWalletPositionEntity openLoss = new SmartWalletPositionEntity()
                .setId(402L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-b")
                .setOutcome("NO")
                .setCostBasisUsdc(new BigDecimal("25.00"))
                .setUnrealizedPnlUsdc(new BigDecimal("-3.40"))
                .setLastMarkPrice(new BigDecimal("0.40"))
                .setStatus("OPEN");

        when(orderMapper.selectList(any())).thenReturn(List.of(orderA, orderB));
        when(fillMapper.selectList(any())).thenReturn(List.of(fillA, fillB));
        when(positionMapper.selectList(any())).thenReturn(List.of(closedWin, openLoss));

        List<SmartWalletFollowAddressDailyReportView> reports = service.followAddressDailyReport(reportDate, 10, "PAPER");

        assertEquals(2, reports.size());
        SmartWalletFollowAddressDailyReportView walletA = reports.stream()
                .filter(item -> "0xaaa".equals(item.getWalletAddress()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, walletA.getWinningPositions());
        assertEquals(0, walletA.getLosingPositions());
        assertEquals(new BigDecimal("12.30"), walletA.getRealizedPnlUsdc());
        assertEquals(new BigDecimal("11.80"), walletA.getNetPnlUsdc());

        SmartWalletFollowAddressDailyReportView walletB = reports.stream()
                .filter(item -> "0xbbb".equals(item.getWalletAddress()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, walletB.getOpenPositions());
        assertEquals(new BigDecimal("-3.40"), walletB.getUnrealizedPnlUsdc());
        assertEquals(new BigDecimal("25.00"), walletB.getGrossExposureUsdc());
        assertEquals(new BigDecimal("-3.60"), walletB.getNetPnlUsdc());
    }

    @Test
    void shouldSplitTimelineProfitLossAndCumulativeLossUsingNetPnl() {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDateTime now = LocalDateTime.now(zoneId).withMinute(0).withSecond(0).withNano(0);
        Date profitAt = Date.from(now.minusHours(6).atZone(zoneId).toInstant());
        Date lossAt = Date.from(now.minusHours(2).atZone(zoneId).toInstant());
        Date feeAt = Date.from(now.minusHours(2).plusMinutes(10).atZone(zoneId).toInstant());

        SmartWalletCashLedgerEntity realizedProfit = new SmartWalletCashLedgerEntity()
                .setExecutionAddress("paper-book")
                .setEntryType("REALIZED_PNL")
                .setDirection("IN")
                .setAmount(new BigDecimal("12.50"))
                .setOccurredAt(profitAt);
        SmartWalletCashLedgerEntity realizedLoss = new SmartWalletCashLedgerEntity()
                .setExecutionAddress("paper-book")
                .setEntryType("REALIZED_PNL")
                .setDirection("OUT")
                .setAmount(new BigDecimal("4.20"))
                .setOccurredAt(lossAt);
        SmartWalletCashLedgerEntity fee = new SmartWalletCashLedgerEntity()
                .setExecutionAddress("paper-book")
                .setEntryType("FEE")
                .setDirection("OUT")
                .setAmount(new BigDecimal("1.30"))
                .setOccurredAt(feeAt);

        when(cashLedgerMapper.selectList(any())).thenReturn(List.of(realizedProfit, realizedLoss, fee));

        List<SmartWalletPnlTimelinePoint> timeline = service.pnlTimeline(1, 8, "PAPER");

        SmartWalletPnlTimelinePoint profitBucket = timeline.stream()
                .filter(point -> new BigDecimal("12.50000000").compareTo(point.getProfitPnlUsdc()) == 0)
                .findFirst()
                .orElseThrow();
        assertEquals(0, new BigDecimal("12.50000000").compareTo(profitBucket.getRealizedPnlUsdc()));
        assertEquals(0, BigDecimal.ZERO.compareTo(profitBucket.getLossPnlUsdc()));

        SmartWalletPnlTimelinePoint lossBucket = timeline.stream()
                .filter(point -> new BigDecimal("-5.50000000").compareTo(point.getLossPnlUsdc()) == 0)
                .findFirst()
                .orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(lossBucket.getProfitPnlUsdc()));
        assertEquals(0, new BigDecimal("-5.50000000").compareTo(lossBucket.getLossPnlUsdc()));
        assertEquals(0, new BigDecimal("-5.50000000").compareTo(lossBucket.getNetPnlUsdc()));
        assertEquals(0, new BigDecimal("-5.50000000").compareTo(lossBucket.getCumulativeLossPnlUsdc()));
    }

    @Test
    void shouldUseNetPnlForLossWhenTimelineFallsBackToDailyReports() {
        SmartWalletDailyReportEntity report = new SmartWalletDailyReportEntity()
                .setReportDate(java.sql.Date.valueOf(LocalDate.now(ZoneId.systemDefault())))
                .setExecutionAddress("paper-book")
                .setRealizedPnlUsdc(new BigDecimal("-4.20"))
                .setFeeUsdc(new BigDecimal("1.30"))
                .setNetPnlUsdc(new BigDecimal("-5.50"));

        when(cashLedgerMapper.selectList(any())).thenReturn(List.of());
        when(dailyReportMapper.selectList(any())).thenReturn(List.of(report));

        List<SmartWalletPnlTimelinePoint> timeline = service.pnlTimeline(7, 24, "PAPER");

        assertEquals(1, timeline.size());
        SmartWalletPnlTimelinePoint point = timeline.get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(point.getProfitPnlUsdc()));
        assertEquals(0, new BigDecimal("-5.50").compareTo(point.getLossPnlUsdc()));
        assertEquals(0, new BigDecimal("-5.50").compareTo(point.getCumulativeLossPnlUsdc()));
        assertEquals(0, new BigDecimal("-5.50").compareTo(point.getNetPnlUsdc()));
    }

    @Test
    void shouldPersistWinningAndLosingPositionsIntoDailyReport() throws Exception {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate reportDate = LocalDate.now(zoneId);
        Date withinDay = Date.from(reportDate.atTime(10, 0).atZone(zoneId).toInstant());
        Date closeAt = Date.from(reportDate.atTime(18, 0).atZone(zoneId).toInstant());

        SmartWalletFillEntity fill = new SmartWalletFillEntity()
                .setId(801L)
                .setExecutionAddress("paper-book")
                .setNotionalUsdc(new BigDecimal("20.00"))
                .setFilledAt(withinDay);

        SmartWalletPositionEntity winPosition = new SmartWalletPositionEntity()
                .setId(901L)
                .setExecutionAddress("paper-book")
                .setStatus("CLAIMED")
                .setRealizedPnlUsdc(new BigDecimal("5.00"))
                .setClosedAt(closeAt);
        SmartWalletPositionEntity lossPosition = new SmartWalletPositionEntity()
                .setId(902L)
                .setExecutionAddress("paper-book")
                .setStatus("CLAIMED")
                .setRealizedPnlUsdc(new BigDecimal("-2.00"))
                .setClosedAt(closeAt);
        SmartWalletPositionEntity breakevenPosition = new SmartWalletPositionEntity()
                .setId(903L)
                .setExecutionAddress("paper-book")
                .setStatus("CLAIMED")
                .setRealizedPnlUsdc(BigDecimal.ZERO)
                .setClosedAt(closeAt);

        SmartWalletCashLedgerEntity realizedProfit = new SmartWalletCashLedgerEntity()
                .setExecutionAddress("paper-book")
                .setEntryType("REALIZED_PNL")
                .setDirection("IN")
                .setAmount(new BigDecimal("5.00"))
                .setOccurredAt(closeAt);
        SmartWalletCashLedgerEntity realizedLoss = new SmartWalletCashLedgerEntity()
                .setExecutionAddress("paper-book")
                .setEntryType("REALIZED_PNL")
                .setDirection("OUT")
                .setAmount(new BigDecimal("2.00"))
                .setOccurredAt(closeAt);

        when(orderMapper.selectList(any())).thenReturn(List.of());
        when(fillMapper.selectList(any())).thenReturn(List.of(fill));
        when(positionMapper.selectList(any())).thenReturn(List.of(winPosition, lossPosition, breakevenPosition));
        when(cashLedgerMapper.selectList(any())).thenReturn(List.of(realizedProfit, realizedLoss));
        when(dailyReportMapper.selectOne(any())).thenReturn(null);

        Method rebuildDailyReport = SmartWalletAccountingService.class
                .getDeclaredMethod("rebuildDailyReport", String.class, LocalDate.class);
        rebuildDailyReport.setAccessible(true);
        rebuildDailyReport.invoke(service, "paper-book", reportDate);

        ArgumentCaptor<SmartWalletDailyReportEntity> reportCaptor = ArgumentCaptor.forClass(SmartWalletDailyReportEntity.class);
        verify(dailyReportMapper).insert(reportCaptor.capture());
        SmartWalletDailyReportEntity saved = reportCaptor.getValue();
        assertEquals(1, saved.getWinningPositions());
        assertEquals(1, saved.getLosingPositions());
        assertEquals(new BigDecimal("0.5000"), saved.getWinRate());
        assertEquals(new BigDecimal("0.5000"), saved.getLossRate());
    }

    @Test
    void shouldExposeOutcomeMetricsInDailyReports() {
        SmartWalletDailyReportEntity entity = new SmartWalletDailyReportEntity()
                .setId(1001L)
                .setReportDate(java.sql.Date.valueOf(LocalDate.of(2026, 4, 23)))
                .setExecutionAddress("paper-book")
                .setTotalOrders(4)
                .setFilledOrders(3)
                .setTotalVolumeUsdc(new BigDecimal("88.00"))
                .setFeeUsdc(new BigDecimal("1.20"))
                .setRealizedPnlUsdc(new BigDecimal("10.00"))
                .setUnrealizedPnlUsdc(BigDecimal.ZERO)
                .setNetPnlUsdc(new BigDecimal("8.80"))
                .setWinningPositions(2)
                .setLosingPositions(1)
                .setWinRate(new BigDecimal("0.6667"))
                .setLossRate(new BigDecimal("0.3333"));

        when(dailyReportMapper.selectList(any())).thenReturn(List.of(entity));
        when(positionMapper.selectList(any())).thenReturn(List.of());

        List<SmartWalletDailyReportView> reports = service.listDailyReports(10, "PAPER");

        assertEquals(1, reports.size());
        SmartWalletDailyReportView report = reports.get(0);
        assertNotNull(report);
        assertEquals(2, report.getWinningPositions());
        assertEquals(1, report.getLosingPositions());
        assertEquals(new BigDecimal("0.3333"), report.getLossRate());
    }
}
