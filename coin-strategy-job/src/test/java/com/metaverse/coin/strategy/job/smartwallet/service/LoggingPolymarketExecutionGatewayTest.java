package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.ExecutionMode;
import com.metaverse.coin.strategy.job.smartwallet.model.PredictionMarketOpportunity;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletExecutionResult;
import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @InjectMocks
    private LoggingPolymarketExecutionGateway gateway;

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
}
