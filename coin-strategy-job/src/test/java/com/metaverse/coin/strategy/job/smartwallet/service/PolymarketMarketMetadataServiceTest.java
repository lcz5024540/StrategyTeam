package com.metaverse.coin.strategy.job.smartwallet.service;
import com.metaverse.coin.strategy.common.service.smartwallet.service.SmartWalletMarketInfoService;

import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketInfoEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;
import java.util.Map;

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
    @Mock
    private SmartWalletMarketInfoService marketInfoService;
    @Spy
    private SmartWalletProperties properties = new SmartWalletProperties();

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
        verify(polymarketApiClient, never()).fetchMarketMetadata(anyString());
    }

    @Test
    void shouldLoadPersistedMarketInfoBeforeApiLookupOnHotPath() {
        when(marketInfoService.listByMarketId("0xdb-market")).thenReturn(List.of(
                new SmartWalletMarketInfoEntity()
                        .setId(1L)
                        .setMarketId("0xdb-market")
                        .setQuestion("Will DB metadata be used?")
                        .setOutcome("YES")
                        .setTokenId("token-yes")
                        .setIndexSet(1)
                        .setAcceptingOrders(true)
                        .setClosed(false)
                        .setMarketLifecycle("ACTIVE")
                        .setLastMetadataSyncAt(new Date()),
                new SmartWalletMarketInfoEntity()
                        .setId(2L)
                        .setMarketId("0xdb-market")
                        .setQuestion("Will DB metadata be used?")
                        .setOutcome("NO")
                        .setTokenId("token-no")
                        .setIndexSet(2)
                        .setAcceptingOrders(true)
                        .setClosed(false)
                        .setMarketLifecycle("ACTIVE")
                        .setLastMetadataSyncAt(new Date())
        ));

        PolymarketApiClient.MarketMetadata metadata = service.getByConditionId("0xdb-market", false, null);

        assertEquals("0xdb-market", metadata.getConditionId());
        assertEquals("Will DB metadata be used?", metadata.getQuestion());
        assertEquals(List.of("YES", "NO"), metadata.getOutcomes());
        assertEquals(Map.of("YES", "token-yes", "NO", "token-no"), metadata.getOutcomeTokenIds());
        assertTrue(metadata.isAcceptingOrders());
        verify(polymarketApiClient, never()).fetchMarketMetadata(anyString());
    }

    @Test
    void shouldRepairIncompletePersistedMetadataOnBestEffortLookup() {
        when(marketInfoService.listByMarketId("0xincomplete")).thenReturn(List.of(
                new SmartWalletMarketInfoEntity()
                        .setId(10L)
                        .setMarketId("0xincomplete")
                        .setQuestion("Team A vs. Team B: O/U 2.5")
                        .setOutcome("OVER")
                        .setAcceptingOrders(true)
                        .setClosed(false)
                        .setMarketLifecycle("ACTIVE")
                        .setLastMetadataSyncAt(new Date())
        ));
        when(polymarketApiClient.fetchMarketMetadata("0xincomplete")).thenReturn(
                PolymarketApiClient.MarketMetadata.builder()
                        .conditionId("0xincomplete")
                        .marketId("market-1")
                        .question("Team A vs. Team B: O/U 2.5")
                        .slug("soc-team-a-team-b-2026-05-04-total-2pt5")
                        .closed(false)
                        .acceptingOrders(true)
                        .outcomes(List.of("OVER", "UNDER"))
                        .outcomeTokenIds(Map.of("OVER", "token-over", "UNDER", "token-under"))
                        .endAt(System.currentTimeMillis() + 3_600_000L)
                        .build()
        );

        PolymarketApiClient.MarketMetadata metadata = service.getBestEffortByConditionId(
                "0xincomplete",
                false,
                "Team A vs. Team B: O/U 2.5",
                List.of("OVER"));

        assertEquals("soc-team-a-team-b-2026-05-04-total-2pt5", metadata.getSlug());
        assertEquals("token-over", metadata.getOutcomeTokenIds().get("OVER"));
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

    @Test
    void shouldResolveCanonicalConditionIdFromSearchMarketIdMatch() {
        when(polymarketApiClient.searchMarkets("76ers vs. Celtics: O/U 217.5", 20, true))
                .thenReturn(List.of(
                        PolymarketApiClient.MarketMetadata.builder()
                                .conditionId("0xcondition")
                                .marketId("market-legacy")
                                .question("76ers vs. Celtics: O/U 217.5")
                                .outcomes(List.of("OVER", "UNDER"))
                                .build()
                ));

        String canonical = service.resolveCanonicalConditionId(
                "market-legacy",
                "76ers vs. Celtics: O/U 217.5",
                List.of("OVER")
        );

        assertEquals("0xcondition", canonical);
    }
}
