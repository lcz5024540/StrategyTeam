package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletPositionEntity;
import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolymarketClobExecutionServiceTest {

    @Mock
    private ExecutionWalletContextLoader walletContextLoader;
    @Mock
    private PolymarketClobClientRouter clobClientRouter;
    @Mock
    private PolymarketApiClient polymarketApiClient;
    @Mock
    private PolymarketMarketDataStreamService marketDataStreamService;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private PolymarketOrderRequestFactory orderRequestFactory;
    @Mock
    private SmartWalletFollowTaskMapper followTaskMapper;
    @Mock
    private SmartWalletFollowTaskService followTaskService;

    @InjectMocks
    private PolymarketClobExecutionService service;

    @Test
    void shouldAllowSyntheticSimulationWhenTokenIdIsMissing() {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .enabled(false)
                .liveReady(false)
                .minOrderUsdc(BigDecimal.ONE)
                .maxOrderUsdc(new BigDecimal("30"))
                .priceSlippageRate(new BigDecimal("0.02"))
                .allowYes(true)
                .allowNo(true)
                .build();
        PolymarketApiClient.MarketMetadata syntheticMetadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xpaper")
                .marketId("0xpaper")
                .question("Synthetic market")
                .outcomes(List.of("YES", "NO"))
                .outcomePrices(List.of("0.5", "0.5"))
                .outcomeTokenIds(Map.of())
                .minimumTickSize(new BigDecimal("0.01"))
                .build();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = PolymarketOrderRequestFactory.PreparedPolymarketOrder.builder()
                .signalId("dry-run-0xpaper-YES")
                .marketId("0xpaper")
                .question("Synthetic market")
                .outcome("YES")
                .side("BUY")
                .stakeUsdc(new BigDecimal("2.00"))
                .limitPrice(new BigDecimal("0.55"))
                .size(new BigDecimal("3.636363"))
                .tickSize(new BigDecimal("0.01"))
                .build();
        when(walletContextLoader.load()).thenReturn(wallet);
        when(marketMetadataService.getBestEffortByConditionId(eq("0xpaper"), eq(false), eq("Synthetic market"), eq(List.of("YES"))))
                .thenReturn(syntheticMetadata);
        when(orderRequestFactory.prepareLocalDraft(
                eq("dry-run-0xpaper-YES"),
                eq("0xpaper"),
                eq("Synthetic market"),
                eq("YES"),
                eq(new BigDecimal("2.00")),
                eq(new BigDecimal("0.54")),
                eq((String) null),
                eq(new BigDecimal("0.01")),
                eq(false),
                eq(wallet)
        )).thenReturn(prepared);

        PolymarketOrderDryRunRequest request = new PolymarketOrderDryRunRequest();
        request.setConditionId("0xpaper");
        request.setQuestion("Synthetic market");
        request.setOutcome("YES");
        request.setStakeUsdc(new BigDecimal("2.00"));
        request.setPrice(new BigDecimal("0.54"));

        Map<String, Object> result = service.dryRunOrder(request);

        assertTrue((Boolean) result.get("success"));
        assertTrue((Boolean) result.get("dryRun"));
        assertTrue((Boolean) result.get("simulationOnly"));
        assertTrue((Boolean) result.get("metadataNotFoundIsNormalInSimulation"));
        assertFalse((Boolean) result.get("walletReady"));
        assertEquals(prepared, result.get("preparedOrder"));
    }

    @Test
    void shouldPreferBestBidWhenSubmittingLiveExit() {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .enabled(true)
                .configured(true)
                .liveReady(true)
                .address("0xlive")
                .orderType("GTC")
                .estimatedFeeRate(new BigDecimal("0.0015"))
                .priceSlippageRate(new BigDecimal("0.02"))
                .build();
        SmartWalletPositionEntity position = new SmartWalletPositionEntity()
                .setId(91L)
                .setExecutionAddress("0xlive")
                .setMarketId("market-live")
                .setTokenId("token-live")
                .setQuestion("Will best bid be used?")
                .setOutcome("YES")
                .setLastMarkPrice(new BigDecimal("0.58"))
                .setAverageEntryPrice(new BigDecimal("0.50"))
                .setQuantity(new BigDecimal("10.00"));
        PolymarketMarketDataStreamService.MarketDataSnapshot snapshot = PolymarketMarketDataStreamService.MarketDataSnapshot.builder()
                .tokenId("token-live")
                .lastTradePrice(new BigDecimal("0.60"))
                .orderBook(PolymarketApiClient.OrderBook.builder()
                        .tokenId("token-live")
                        .midpoint(new BigDecimal("0.615"))
                        .bids(List.of(PolymarketApiClient.OrderBookLevel.builder()
                                .price(new BigDecimal("0.61"))
                                .size(new BigDecimal("100"))
                                .build()))
                        .asks(List.of(PolymarketApiClient.OrderBookLevel.builder()
                                .price(new BigDecimal("0.62"))
                                .size(new BigDecimal("100"))
                                .build()))
                        .build())
                .build();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = PolymarketOrderRequestFactory.PreparedPolymarketOrder.builder()
                .signalId("position-exit-91")
                .marketId("market-live")
                .question("Will best bid be used?")
                .outcome("YES")
                .side("SELL")
                .stakeUsdc(new BigDecimal("6.10"))
                .limitPrice(new BigDecimal("0.60"))
                .size(new BigDecimal("10.000000"))
                .tokenId("token-live")
                .tickSize(new BigDecimal("0.01"))
                .build();

        when(walletContextLoader.requireLiveContext()).thenReturn(wallet);
        when(marketMetadataService.getBestEffortByConditionId(eq("market-live"), eq(false), eq("Will best bid be used?"), eq(List.of("YES"))))
                .thenReturn(PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("market-live")
                        .marketId("market-live")
                        .question("Will best bid be used?")
                        .outcomeTokenIds(Map.of("YES", "token-live"))
                        .minimumTickSize(new BigDecimal("0.01"))
                        .build());
        when(marketDataStreamService.getSnapshot("token-live")).thenReturn(snapshot);
        // F-P0-1: PolymarketClobExecutionService now always calls the 4-arg prepareExit overload
        // (overrideQuantity=null for full exit, > 0 for partial). Stub the 4-arg form.
        when(orderRequestFactory.prepareExit(eq(position), eq(new BigDecimal("0.61")), eq(wallet), eq((BigDecimal) null))).thenReturn(prepared);
        when(clobClientRouter.placeOrder(eq(wallet), eq(prepared))).thenReturn(Map.of(
                "success", true,
                "orderID", "order-live-1",
                "response", Map.of("status", "OPEN")
        ));

        SmartWalletExecutionResult result = service.executeLiveExit(position, null, "TAKE_PROFIT_AUTO_EXIT");

        assertEquals(ExecutionMode.LIVE, result.getMode());
        assertTrue(result.isSuccess());
        assertEquals("order-live-1", result.getExternalOrderId());
        assertEquals("LIVE", result.getStatus());
        assertTrue(result.getMessage().contains("pricingSource=WS_BEST_BID"));
    }
}
