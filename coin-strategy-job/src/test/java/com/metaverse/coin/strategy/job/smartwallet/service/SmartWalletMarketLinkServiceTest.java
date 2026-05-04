package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.job.smartwallet.model.SmartWalletMarketLinkView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartWalletMarketLinkServiceTest {

    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private SmartWalletMarketStateService marketStateService;

    @Test
    void shouldReturnExternalUrlWhenStoredSlugExists() {
        SmartWalletMarketLinkService service = new SmartWalletMarketLinkService(marketMetadataService, marketStateService);

        SmartWalletMarketLinkView view = service.fromStoredSlug(
                "condition-1",
                "Will Team A win?",
                "YES",
                "mlb-sf-phi-2026-04-30-over-under-8pt5");

        assertEquals(SmartWalletMarketLinkService.STATUS_EXTERNAL_READY, view.getLinkStatus());
        assertEquals("https://polymarket.com/sports/mlb/mlb-sf-phi-2026-04-30", view.getExternalMarketUrl());
        assertEquals(view.getExternalMarketUrl(), view.getMarketUrl());
    }

    @Test
    void shouldReturnInternalFallbackWhenSlugMissing() {
        SmartWalletMarketLinkService service = new SmartWalletMarketLinkService(marketMetadataService, marketStateService);
        when(marketMetadataService.getBestEffortByConditionId(eq("condition-2"), eq(false), eq("Question"), eq(List.of("YES"))))
                .thenReturn(PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("condition-2")
                        .question("Question")
                        .outcomes(List.of("YES", "NO"))
                        .outcomeTokenIds(Map.of())
                        .build());

        SmartWalletMarketLinkView view = service.resolve("condition-2", "Question", "YES");

        assertEquals(SmartWalletMarketLinkService.STATUS_INTERNAL_FALLBACK, view.getLinkStatus());
        assertNull(view.getExternalMarketUrl());
        assertEquals("/dashboard/polymarket/markets/condition-2", view.getMarketUrl());
    }
}
