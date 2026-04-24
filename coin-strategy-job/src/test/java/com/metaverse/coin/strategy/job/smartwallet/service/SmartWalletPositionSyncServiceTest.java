package com.metaverse.coin.strategy.job.smartwallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletOrderMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitResultView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletPositionSyncServiceTest {

    @Mock
    private PolymarketApiClient polymarketApiClient;
    @Mock
    private ExecutionWalletContextLoader walletContextLoader;
    @Mock
    private SmartWalletPositionMapper positionMapper;
    @Mock
    private SmartWalletOrderMapper orderMapper;
    @Mock
    private SmartWalletAccountingService accountingService;
    @Mock
    private PolymarketClobExecutionService clobExecutionService;
    @Mock
    private SmartWalletRecoveryService recoveryService;
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Mock
    private ExitSignalProvider exitSignalProvider;
    @Mock
    private WalletBehaviorProfileService walletBehaviorProfileService;

    private SmartWalletProperties properties;
    private SmartWalletPositionSyncService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        properties.setEnabled(true);
        properties.getPolymarket().setEnabled(true);
        when(exitSignalProvider.resolveStopLossThreshold(any())).thenReturn(new BigDecimal("0.20"));
        when(exitSignalProvider.isTrendStopLossTriggered(any(), any(), any())).thenReturn(false);
        doNothing().when(exitSignalProvider).recordMarkPrice(any(), any(), any());
        service = new SmartWalletPositionSyncService(
                properties,
                polymarketApiClient,
                walletContextLoader,
                positionMapper,
                orderMapper,
                accountingService,
                clobExecutionService,
                recoveryService,
                marketInfoService,
                exitSignalProvider,
                walletBehaviorProfileService
        );
    }

    @Test
    void shouldSkipSyncWhenOnlyPaperFlowsAreActive() {
        when(positionMapper.selectCount(any())).thenReturn(0L);

        service.syncPositions();

        verify(walletContextLoader, never()).requireLiveContext();
        verify(polymarketApiClient, never()).fetchBalance(any());
        verify(polymarketApiClient, never()).fetchPositions(any(), any());
    }

    @Test
    void shouldSyncWhenLiveExecutionIsEnabled() {
        properties.setAutoExecutionEnabled(true);
        ExecutionWalletContextLoader.ExecutionWalletContext context = ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .address("0x123")
                .build();
        when(walletContextLoader.requireLiveContext()).thenReturn(context);
        when(polymarketApiClient.fetchBalance("0x123")).thenReturn(new BigDecimal("12.34"));
        when(polymarketApiClient.fetchPositions("0x123", null)).thenReturn(List.<JsonNode>of());

        service.syncPositions();

        verify(polymarketApiClient).fetchBalance("0x123");
        verify(polymarketApiClient).fetchPositions("0x123", null);
    }

    @Test
    void shouldSyncWhenLivePositionsExistLocallyEvenIfPaperFlowIsDefault() {
        ExecutionWalletContextLoader.ExecutionWalletContext context = ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .address("0x123")
                .build();
        when(positionMapper.selectCount(any())).thenReturn(1L);
        when(walletContextLoader.requireLiveContext()).thenReturn(context);
        when(polymarketApiClient.fetchBalance("0x123")).thenReturn(BigDecimal.ZERO);
        when(polymarketApiClient.fetchPositions("0x123", null)).thenReturn(List.<JsonNode>of());

        service.syncPositions();

        verify(polymarketApiClient).fetchBalance("0x123");
        verify(polymarketApiClient).fetchPositions("0x123", null);
    }

    @Test
    void shouldCooldownAfterNotFoundResponse() {
        properties.setAutoExecutionEnabled(true);
        properties.getPolymarket().setPositionSyncNotFoundCooldownMs(60_000L);
        ExecutionWalletContextLoader.ExecutionWalletContext context = ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .address("0x123")
                .build();
        when(walletContextLoader.requireLiveContext()).thenReturn(context);
        when(polymarketApiClient.fetchBalance("0x123"))
                .thenThrow(new IllegalStateException("Polymarket API request failed: https://data-api.polymarket.com/balance?user=0x123, status=404"));

        service.syncPositions();
        service.syncPositions();

        verify(walletContextLoader, times(1)).requireLiveContext();
        verify(polymarketApiClient, times(1)).fetchBalance(eq("0x123"));
        verify(polymarketApiClient, never()).fetchPositions(any(), any());
    }

    @Test
    void shouldUseUnifiedExitFlowWhenStopLossTriggers() {
        properties.getSignalRisk().setEnableAutoStopLoss(true);
        properties.getSignalRisk().setAutoStopLossThresholdRate(new BigDecimal("0.20"));
        when(accountingService.exitPosition(any(), any())).thenReturn(SmartWalletPositionExitResultView.builder()
                .positionId(11L)
                .currentStatus("CLOSED")
                .realizedPnlDeltaUsdc(new BigDecimal("-2.00"))
                .build());
        when(positionMapper.selectList(any())).thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                .setId(11L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-1")
                .setOutcome("YES")
                .setAverageEntryPrice(new BigDecimal("0.60"))
                .setLastMarkPrice(new BigDecimal("0.40"))
                .setQuantity(new BigDecimal("5.00"))
                .setStatus("OPEN")));

        service.checkStopLoss();

        verify(accountingService).exitPosition(eq(11L), any());
    }

    @Test
    void shouldUseThresholdPriceForPaperStopLossExit() {
        properties.getSignalRisk().setEnableAutoStopLoss(true);
        when(exitSignalProvider.resolveStopLossThreshold(any())).thenReturn(new BigDecimal("0.25"));
        when(accountingService.exitPosition(any(), any())).thenReturn(SmartWalletPositionExitResultView.builder()
                .positionId(44L)
                .currentStatus("CLOSED")
                .realizedPnlDeltaUsdc(new BigDecimal("-37.50"))
                .build());
        when(positionMapper.selectList(any())).thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                .setId(44L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-stop")
                .setOutcome("YES")
                .setAverageEntryPrice(new BigDecimal("0.68"))
                .setLastMarkPrice(new BigDecimal("0.04"))
                .setQuantity(new BigDecimal("220.58823529"))
                .setCostBasisUsdc(new BigDecimal("150.00"))
                .setStatus("OPEN")));

        service.checkStopLoss();

        ArgumentCaptor<com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest> captor =
                ArgumentCaptor.forClass(com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest.class);
        verify(accountingService).exitPosition(eq(44L), captor.capture());
        assertEquals(new BigDecimal("0.51000000"), captor.getValue().getPrice());
    }

    @Test
    void shouldUseUnifiedExitFlowWhenTakeProfitTriggers() {
        properties.getSignalRisk().setEnableAutoTakeProfit(true);
        properties.getSignalRisk().setAutoTakeProfitThresholdRate(new BigDecimal("0.15"));
        when(accountingService.exitPosition(any(), any())).thenReturn(SmartWalletPositionExitResultView.builder()
                .positionId(21L)
                .currentStatus("CLOSED")
                .realizedPnlDeltaUsdc(new BigDecimal("3.00"))
                .build());
        when(positionMapper.selectList(any())).thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                .setId(21L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-2")
                .setOutcome("YES")
                .setAverageEntryPrice(new BigDecimal("0.50"))
                .setLastMarkPrice(new BigDecimal("0.62"))
                .setQuantity(new BigDecimal("10.00"))
                .setCostBasisUsdc(new BigDecimal("5.00"))
                .setStatus("OPEN")));

        service.checkTakeProfit();

        verify(accountingService).exitPosition(eq(21L), any());
    }

    @Test
    void shouldUseReachableDynamicTakeProfitThresholdForHighEntryBinaryPosition() {
        properties.getSignalRisk().setEnableAutoTakeProfit(true);
        properties.getSignalRisk().setAutoTakeProfitThresholdRate(new BigDecimal("0.60"));
        when(accountingService.exitPosition(any(), any())).thenReturn(SmartWalletPositionExitResultView.builder()
                .positionId(29L)
                .currentStatus("CLOSED")
                .realizedPnlDeltaUsdc(new BigDecimal("1.00"))
                .build());
        when(positionMapper.selectList(any())).thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                .setId(29L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-tp")
                .setOutcome("YES")
                .setAverageEntryPrice(new BigDecimal("0.84"))
                .setLastMarkPrice(new BigDecimal("0.99"))
                .setQuantity(new BigDecimal("10.00"))
                .setCostBasisUsdc(new BigDecimal("8.40"))
                .setStatus("OPEN")));

        service.checkTakeProfit();

        ArgumentCaptor<com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest> captor =
                ArgumentCaptor.forClass(com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest.class);
        verify(accountingService).exitPosition(eq(29L), captor.capture());
        assertEquals(new BigDecimal("0.96800000"), captor.getValue().getPrice());
    }

    @Test
    void shouldTightenTakeProfitThresholdForEarlyTakeProfitWallet() {
        properties.getSignalRisk().setEnableAutoTakeProfit(true);
        properties.getSignalRisk().setAutoTakeProfitThresholdRate(new BigDecimal("0.20"));
        when(orderMapper.selectOne(any())).thenReturn(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity()
                .setPositionId(55L)
                .setWalletAddress("0xwallet"));
        when(walletBehaviorProfileService.recommendedExitLane("0xwallet")).thenReturn("EARLY_TAKE_PROFIT");
        when(accountingService.exitPosition(any(), any())).thenReturn(SmartWalletPositionExitResultView.builder()
                .positionId(55L)
                .currentStatus("CLOSED")
                .realizedPnlDeltaUsdc(new BigDecimal("0.75"))
                .build());
        when(positionMapper.selectList(any())).thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                .setId(55L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-wallet-tp")
                .setOutcome("YES")
                .setAverageEntryPrice(new BigDecimal("0.50"))
                .setLastMarkPrice(new BigDecimal("0.66"))
                .setQuantity(new BigDecimal("10.00"))
                .setCostBasisUsdc(new BigDecimal("5.00"))
                .setStatus("OPEN")));

        service.checkTakeProfit();

        ArgumentCaptor<com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest> captor =
                ArgumentCaptor.forClass(com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest.class);
        verify(accountingService).exitPosition(eq(55L), captor.capture());
        assertEquals(new BigDecimal("0.57500000"), captor.getValue().getPrice());
    }

    @Test
    void shouldTightenStopLossThresholdForFastStopLossWallet() {
        properties.getSignalRisk().setEnableAutoStopLoss(true);
        when(exitSignalProvider.resolveStopLossThreshold(any())).thenReturn(new BigDecimal("0.25"));
        when(orderMapper.selectOne(any())).thenReturn(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletOrderEntity()
                .setPositionId(56L)
                .setWalletAddress("0xwallet"));
        when(walletBehaviorProfileService.recommendedExitLane("0xwallet")).thenReturn("FAST_STOP_LOSS");
        when(accountingService.exitPosition(any(), any())).thenReturn(SmartWalletPositionExitResultView.builder()
                .positionId(56L)
                .currentStatus("CLOSED")
                .realizedPnlDeltaUsdc(new BigDecimal("-30.00"))
                .build());
        when(positionMapper.selectList(any())).thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                .setId(56L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-wallet-sl")
                .setOutcome("YES")
                .setAverageEntryPrice(new BigDecimal("0.68"))
                .setLastMarkPrice(new BigDecimal("0.50"))
                .setQuantity(new BigDecimal("220.58823529"))
                .setCostBasisUsdc(new BigDecimal("150.00"))
                .setStatus("OPEN")));

        service.checkStopLoss();

        ArgumentCaptor<com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest> captor =
                ArgumentCaptor.forClass(com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletPositionExitRequest.class);
        verify(accountingService).exitPosition(eq(56L), captor.capture());
        assertEquals(new BigDecimal("0.54400000"), captor.getValue().getPrice());
    }

    @Test
    void shouldSubmitLiveExitWhenTakeProfitTriggersOnLivePosition() {
        properties.getSignalRisk().setEnableAutoTakeProfit(true);
        properties.getSignalRisk().setAutoTakeProfitThresholdRate(new BigDecimal("0.15"));
        ExecutionWalletContextLoader.ExecutionWalletContext context = ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .address("0xlive")
                .enabled(true)
                .configured(true)
                .liveReady(true)
                .build();
        when(walletContextLoader.requireLiveContext()).thenReturn(context);
        when(orderMapper.selectCount(any())).thenReturn(0L);
        when(positionMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                        .setId(31L)
                        .setExecutionAddress("0xlive")
                        .setMarketId("market-live")
                        .setOutcome("YES")
                        .setLastMarkPrice(new BigDecimal("0.62"))
                        .setQuantity(new BigDecimal("10.00"))
                        .setCostBasisUsdc(new BigDecimal("5.00"))
                        .setStatus("OPEN")));
        when(clobExecutionService.executeLiveExit(any(), any(), any())).thenReturn(SmartWalletExecutionResult.builder()
                .mode(ExecutionMode.LIVE)
                .success(true)
                .externalOrderId("live-order-1")
                .status("LIVE")
                .side("SELL")
                .price(new BigDecimal("0.60"))
                .size(new BigDecimal("10.00"))
                .notionalUsdc(new BigDecimal("6.00"))
                .build());

        service.checkTakeProfit();

        verify(clobExecutionService).executeLiveExit(any(), any(), eq("TAKE_PROFIT_AUTO_EXIT"));
        verify(accountingService).recordLiveExitExecution(any(), any(), eq("TAKE_PROFIT_AUTO_EXIT"));
    }

    @Test
    void shouldNotExitWhenTakeProfitThresholdNotReached() {
        properties.getSignalRisk().setEnableAutoTakeProfit(true);
        properties.getSignalRisk().setAutoTakeProfitThresholdRate(new BigDecimal("0.30"));
        when(positionMapper.selectList(any())).thenReturn(List.of(new com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity()
                .setId(22L)
                .setExecutionAddress("paper-book")
                .setMarketId("market-3")
                .setOutcome("NO")
                .setAverageEntryPrice(new BigDecimal("0.50"))
                .setLastMarkPrice(new BigDecimal("0.58"))
                .setQuantity(new BigDecimal("10.00"))
                .setCostBasisUsdc(new BigDecimal("5.00"))
                .setStatus("OPEN")));

        service.checkTakeProfit();

        verify(accountingService, never()).exitPosition(eq(22L), any());
    }
}
