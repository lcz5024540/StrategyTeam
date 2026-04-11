package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolymarketMarketMetadataServiceTest {

    @Mock
    private PolymarketApiClient polymarketApiClient;

    @InjectMocks
    private PolymarketMarketMetadataService service;

    @Test
    void shouldPreferRegisteredLocalMetadataBeforeApiLookup() {
        service.registerLocalMetadata("0xpaper-market", "Mock market", "SPORTS", List.of("YES"));

        PolymarketApiClient.MarketMetadata metadata = service.getByConditionId("0xpaper-market", false, "Ignored question");

        assertEquals("0xpaper-market", metadata.getConditionId());
        assertEquals("Mock market", metadata.getQuestion());
        assertEquals(List.of("YES", "NO"), metadata.getOutcomes());
        verify(polymarketApiClient, never()).fetchMarketMetadata(anyString());
    }

    @Test
    void shouldReturnSyntheticMetadataWhenLookupFails() {
        when(polymarketApiClient.fetchMarketMetadata("0xmissing-market"))
                .thenThrow(new IllegalStateException("Polymarket market metadata not found"));
        when(polymarketApiClient.searchMarkets("Synthetic market", 10, true))
                .thenReturn(List.of());

        PolymarketApiClient.MarketMetadata metadata = service.getBestEffortByConditionId(
                "0xmissing-market",
                false,
                "Synthetic market",
                List.of("NO"));

        assertEquals("0xmissing-market", metadata.getConditionId());
        assertEquals("Synthetic market", metadata.getQuestion());
        assertEquals(List.of("NO", "YES"), metadata.getOutcomes());
        assertFalse(metadata.isResolved());
        assertFalse(metadata.isClosed());
        assertTrue(metadata.getOutcomeTokenIds().isEmpty());
    }
}
