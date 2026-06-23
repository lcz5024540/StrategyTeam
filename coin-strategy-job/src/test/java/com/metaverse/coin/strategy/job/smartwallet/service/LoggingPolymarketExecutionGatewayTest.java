package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoggingPolymarketExecutionGatewayTest {

    @Mock
    private ExecutionWalletContextLoader walletContextLoader;
    @Mock
    private PolymarketOrderRequestFactory orderRequestFactory;
    @Mock
    private PolymarketClobClientRouter clobClientRouter;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;

    private LoggingPolymarketExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new LoggingPolymarketExecutionGateway(
                walletContextLoader,
                orderRequestFactory,
                clobClientRouter,
                marketMetadataService,
                new SmartWalletProperties()
        );
    }

    @Test
    void shouldKeepLiveOrdersOpenUntilPolymarketReportsFill() {
        ExecutionWalletContextLoader.ExecutionWalletContext wallet = ExecutionWalletContextLoader.ExecutionWalletContext.builder()
                .address("0xabc")
                .orderType("GTC")
                .build();
        PolymarketOrderRequestFactory.PreparedPolymarketOrder prepared = PolymarketOrderRequestFactory.PreparedPolymarketOrder.builder()
                .signalId("signal-1")
                .marketId("condition-1")
                .tokenId("token-1")
                .outcome("YES")
                .side("BUY")
                .limitPrice(new BigDecimal("0.52"))
                .size(new BigDecimal("9.615384"))
                .stakeUsdc(new BigDecimal("5.00"))
                .build();
        SmartWalletSignal signal = SmartWalletSignal.builder()
                .signalId("signal-1")
                .followOutcome("YES")
                .recommendedStakeUsdc(new BigDecimal("5.00"))
                .market(PredictionMarketOpportunity.builder()
                        .marketId("condition-1")
                        .question("Will dry-run succeed?")
                        .price(new BigDecimal("0.50"))
                        .build())
                .build();

        when(walletContextLoader.requireLiveContext()).thenReturn(wallet);
        when(orderRequestFactory.prepare(signal, wallet)).thenReturn(prepared);
        when(clobClientRouter.placeOrder(wallet, prepared)).thenReturn(Map.of(
                "success", true,
                "orderID", "ord-1",
                "response", Map.of("status", "OPEN")
        ));

        SmartWalletExecutionResult result = gateway.execute(signal, ExecutionMode.LIVE);

        assertTrue(result.isSuccess());
        assertEquals("LIVE", result.getStatus());
        assertEquals("ord-1", result.getExternalOrderId());
    }

    @Test
    void shouldAttachTokenIdForPaperOrdersWhenMetadataIsAvailable() {
        SmartWalletSignal signal = SmartWalletSignal.builder()
                .signalId("signal-paper")
                .followOutcome("YES")
                .side("BUY")
                .recommendedStakeUsdc(new BigDecimal("5.00"))
                .market(PredictionMarketOpportunity.builder()
                        .marketId("condition-paper")
                        .question("Will paper token id be captured?")
                        .price(new BigDecimal("0.50"))
                        .build())
                .build();
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("condition-paper")
                .marketId("condition-paper")
                .outcomes(List.of("YES", "NO"))
                .outcomeTokenIds(Map.of("YES", "token-yes", "NO", "token-no"))
                .build();

        when(marketMetadataService.getBestEffortByConditionId("condition-paper", false,
                "Will paper token id be captured?", List.of("YES")))
                .thenReturn(metadata);

        SmartWalletExecutionResult result = gateway.execute(signal, ExecutionMode.PAPER);

        assertTrue(result.isSuccess());
        assertEquals("token-yes", result.getTokenId());
    }

    @Test
    void shouldRejectPaperSellSignalBeforeCreatingEntryOrder() {
        SmartWalletSignal signal = SmartWalletSignal.builder()
                .signalId("signal-sell")
                .followOutcome("NO")
                .side("SELL")
                .recommendedStakeUsdc(new BigDecimal("5.00"))
                .market(PredictionMarketOpportunity.builder()
                        .marketId("condition-sell")
                        .question("Will sell be blocked?")
                        .price(new BigDecimal("0.40"))
                        .build())
                .build();

        SmartWalletExecutionResult result = gateway.execute(signal, ExecutionMode.PAPER);

        assertFalse(result.isSuccess());
        assertEquals("SKIPPED", result.getStatus());
        assertEquals("SELL", result.getSide());
        assertTrue(result.getMessage().contains("SELL_ENTRY_BLOCKED"));
    }
}
