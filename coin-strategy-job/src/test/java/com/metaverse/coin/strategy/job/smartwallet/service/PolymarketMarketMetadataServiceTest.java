package com.metaverse.coin.strategy.job.smartwallet.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void shouldUpgradeToResolvedFromExactSearchFallbackWhenPrimaryIsStale() {
        PolymarketApiClient.MarketMetadata primary = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xmarket")
                .marketId("market-1")
                .question("Will stale winner be repaired?")
                .closed(false)
                .acceptingOrders(false)
                .resolvedOutcome(null)
                .endAt(System.currentTimeMillis() - 60_000L)
                .build();
        PolymarketApiClient.MarketMetadata fallback = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xmarket")
                .marketId("market-1")
                .question("Will stale winner be repaired?")
                .closed(true)
                .acceptingOrders(false)
                .resolvedOutcome("YES")
                .build();
        when(polymarketApiClient.fetchMarketMetadata("0xmarket")).thenReturn(primary);
        when(polymarketApiClient.searchMarkets("Will stale winner be repaired?", 10, true)).thenReturn(List.of(fallback));

        PolymarketApiClient.MarketMetadata metadata = service.getByConditionId("0xmarket", true, "Will stale winner be repaired?");

        assertTrue(metadata.isResolved());
        assertEquals("YES", metadata.getResolvedOutcome());
        assertEquals("GAMMA_SEARCH_EXACT", metadata.getResolutionSource());
        assertNotNull(metadata.getResolutionObservedAt());
    }

    @Test
    void shouldIgnoreQuestionOnlyResolvedFallbackWhenConditionIdDiffers() {
        PolymarketApiClient.MarketMetadata primary = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xmarket")
                .marketId("market-1")
                .question("Will question-only fallback be ignored?")
                .closed(false)
                .acceptingOrders(false)
                .resolvedOutcome(null)
                .endAt(System.currentTimeMillis() - 60_000L)
                .build();
        PolymarketApiClient.MarketMetadata fallback = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xother")
                .marketId("market-2")
                .question("Will question-only fallback be ignored?")
                .closed(true)
                .acceptingOrders(false)
                .resolvedOutcome("NO")
                .build();
        when(polymarketApiClient.fetchMarketMetadata("0xmarket")).thenReturn(primary);
        when(polymarketApiClient.searchMarkets("Will question-only fallback be ignored?", 10, true)).thenReturn(List.of(fallback));

        PolymarketApiClient.MarketMetadata metadata = service.getByConditionId("0xmarket", true, "Will question-only fallback be ignored?");

        assertFalse(metadata.isResolved());
    }

    @Test
    void shouldKeepCachedResolvedMetadataMonotonicWhenPrimaryRegresses() {
        PolymarketApiClient.MarketMetadata resolved = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xmarket")
                .marketId("market-1")
                .question("Will resolved metadata stay sticky?")
                .closed(true)
                .acceptingOrders(false)
                .resolvedOutcome("YES")
                .build();
        PolymarketApiClient.MarketMetadata regressed = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xmarket")
                .marketId("market-1")
                .question("Will resolved metadata stay sticky?")
                .closed(false)
                .acceptingOrders(false)
                .resolvedOutcome(null)
                .endAt(System.currentTimeMillis() - 60_000L)
                .build();
        when(polymarketApiClient.fetchMarketMetadata("0xmarket")).thenReturn(resolved, regressed);

        PolymarketApiClient.MarketMetadata first = service.getByConditionId("0xmarket", true, "Will resolved metadata stay sticky?");
        PolymarketApiClient.MarketMetadata second = service.getByConditionId("0xmarket", true, "Will resolved metadata stay sticky?");

        assertTrue(first.isResolved());
        assertTrue(second.isResolved());
        assertEquals("YES", second.getResolvedOutcome());
    }

    @Test
    void shouldResolveOutcomeAndWinningTokenByTokenId() {
        PolymarketApiClient.MarketMetadata metadata = PolymarketApiClient.MarketMetadata.builder()
                .conditionId("0xmarket")
                .marketId("market-1")
                .resolvedOutcome("YES")
                .outcomeTokenIds(java.util.Map.of("YES", "token-yes", "NO", "token-no"))
                .build();

        assertEquals("YES", service.resolveOutcomeByTokenId(metadata, "token-yes", "NO"));
        assertEquals("token-yes", service.resolveWinningTokenId(metadata));
    }
}
