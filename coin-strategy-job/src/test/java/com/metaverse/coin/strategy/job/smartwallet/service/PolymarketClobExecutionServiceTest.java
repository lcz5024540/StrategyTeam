package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletFollowTaskMapper;
import com.metaverse.coin.strategy.job.smartwallet.model.PolymarketOrderDryRunRequest;
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
}
