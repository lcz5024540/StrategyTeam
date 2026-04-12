package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletCashLedgerMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletPositionMapper;
import com.metaverse.coin.strategy.job.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private SmartWalletCashLedgerMapper cashLedgerMapper;

    private SmartWalletProperties properties;
    private SmartWalletPositionSyncService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        properties.setEnabled(true);
        properties.getPolymarket().setEnabled(true);
        service = new SmartWalletPositionSyncService(
                properties,
                polymarketApiClient,
                walletContextLoader,
                positionMapper,
                cashLedgerMapper
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
}
