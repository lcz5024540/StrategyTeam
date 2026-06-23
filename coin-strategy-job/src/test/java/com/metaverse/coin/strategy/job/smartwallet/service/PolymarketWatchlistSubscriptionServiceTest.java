package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletProfileEntity;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletTradeEventEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-P1-7 / F-P1-8 第十一轮：{@link PolymarketWatchlistSubscriptionService} 单测。
 *
 * <p>覆盖：
 * <ol>
 *   <li>主开关 OFF（enableWsClient=false 或 enableWsAutoSubscribe=false）时 noop；</li>
 *   <li>开关 ON 时增量推送 HOT 钱包 + 活跃市场 tokenIds；</li>
 *   <li>已订阅集合幂等：同一 wallet / token 第二次刷新不再推送；</li>
 *   <li>批量上限：超过 maxNewWalletsPerCycle 时本周期只推一批，剩余下周期继续；</li>
 *   <li>metadata 异常时跳过该 marketId 不抛出，计入 tokenLookupFailures；</li>
 *   <li>resetSubscriptionState 后重新推全量；</li>
 *   <li>stats 字段透出正确。</li>
 * </ol>
 */
class PolymarketWatchlistSubscriptionServiceTest {

    private SmartWalletProperties properties;
    private PolymarketWebSocketClient wsClient;
    private SmartWalletProfileStoreService profileStoreService;
    private SmartWalletTradeEventMapper tradeEventMapper;
    private PolymarketMarketMetadataService marketMetadataService;
    private PolymarketWatchlistSubscriptionService service;

    @BeforeEach
    void setUp() {
        properties = new SmartWalletProperties();
        properties.getPolymarket().setEnableWsClient(true);
        properties.getPolymarket().setEnableWsAutoSubscribe(true);
        properties.getPolymarket().setWsAutoSubscribeMaxNewMarketsPerCycle(200);
        properties.getPolymarket().setWsAutoSubscribeMaxNewWalletsPerCycle(200);

        wsClient = mock(PolymarketWebSocketClient.class);
        profileStoreService = mock(SmartWalletProfileStoreService.class);
        tradeEventMapper = mock(SmartWalletTradeEventMapper.class);
        marketMetadataService = mock(PolymarketMarketMetadataService.class);
        // default to no-op behavior; individual tests stub specific behavior
        lenient().when(wsClient.subscribeMarkets(any())).thenReturn(true);
        lenient().when(wsClient.subscribeUsers(any())).thenReturn(true);
        lenient().when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of());
        lenient().when(tradeEventMapper.selectList(any())).thenReturn(List.of());

        service = new PolymarketWatchlistSubscriptionService(
                properties, wsClient, profileStoreService, tradeEventMapper, marketMetadataService);
    }

    @Test
    void refresh_shouldNoOpWhenWsClientDisabled() {
        properties.getPolymarket().setEnableWsClient(false);

        service.refreshSubscriptions();

        verify(wsClient, never()).subscribeMarkets(any());
        verify(wsClient, never()).subscribeUsers(any());
    }

    @Test
    void refresh_shouldNoOpWhenAutoSubscribeDisabled() {
        properties.getPolymarket().setEnableWsAutoSubscribe(false);

        service.refreshSubscriptions();

        verify(wsClient, never()).subscribeMarkets(any());
        verify(wsClient, never()).subscribeUsers(any());
    }

    @Test
    void refresh_shouldPushNewHotWalletsAndMarkets() {
        // 1) HOT wallets
        SmartWalletProfileEntity hot1 = walletProfile("0xAAA");
        SmartWalletProfileEntity hot2 = walletProfile("0xBBB");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(hot1, hot2));
        // 2) Recent trade events
        SmartWalletTradeEventEntity ev1 = tradeEvent("market-1");
        SmartWalletTradeEventEntity ev2 = tradeEvent("market-2");
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(ev1, ev2));
        // 3) Metadata returns outcome → token map
        when(marketMetadataService.getByConditionId("market-1"))
                .thenReturn(metadata(Map.of("YES", "token-1A", "NO", "token-1B")));
        when(marketMetadataService.getByConditionId("market-2"))
                .thenReturn(metadata(Map.of("YES", "token-2A", "NO", "token-2B")));

        service.refreshSubscriptions();

        ArgumentCaptor<List<String>> walletCaptor = ArgumentCaptor.forClass(List.class);
        verify(wsClient, times(1)).subscribeUsers(walletCaptor.capture());
        assertTrue(walletCaptor.getValue().contains("0xaaa"));
        assertTrue(walletCaptor.getValue().contains("0xbbb"));

        ArgumentCaptor<List<String>> marketCaptor = ArgumentCaptor.forClass(List.class);
        verify(wsClient, times(1)).subscribeMarkets(marketCaptor.capture());
        assertEquals(4, marketCaptor.getValue().size());
        assertTrue(marketCaptor.getValue().contains("token-1A"));
        assertTrue(marketCaptor.getValue().contains("token-2B"));
    }

    @Test
    void refresh_secondCycleShouldOnlyPushDelta() {
        SmartWalletProfileEntity hot1 = walletProfile("0xAAA");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(hot1));
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(tradeEvent("market-1")));
        when(marketMetadataService.getByConditionId(anyString()))
                .thenReturn(metadata(Map.of("YES", "token-1A")));

        service.refreshSubscriptions();
        // 第二轮：内容没变化，应该不再推送
        service.refreshSubscriptions();

        verify(wsClient, times(1)).subscribeUsers(any());
        verify(wsClient, times(1)).subscribeMarkets(any());
    }

    @Test
    void refresh_secondCycleShouldPushNewArrivals() {
        SmartWalletProfileEntity hot1 = walletProfile("0xAAA");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(hot1));
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(tradeEvent("market-1")));
        when(marketMetadataService.getByConditionId(anyString()))
                .thenReturn(metadata(Map.of("YES", "token-1A")));

        service.refreshSubscriptions();

        // 第二轮新增一个钱包 + 一个市场
        SmartWalletProfileEntity hot2 = walletProfile("0xBBB");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(hot1, hot2));
        when(tradeEventMapper.selectList(any()))
                .thenReturn(List.of(tradeEvent("market-1"), tradeEvent("market-2")));
        when(marketMetadataService.getByConditionId("market-2"))
                .thenReturn(metadata(Map.of("YES", "token-2A")));

        service.refreshSubscriptions();

        verify(wsClient, times(2)).subscribeUsers(any());
        verify(wsClient, times(2)).subscribeMarkets(any());
    }

    @Test
    void refresh_shouldRespectMaxNewWalletsPerCycle() {
        properties.getPolymarket().setWsAutoSubscribeMaxNewWalletsPerCycle(1);
        SmartWalletProfileEntity hot1 = walletProfile("0xAAA");
        SmartWalletProfileEntity hot2 = walletProfile("0xBBB");
        SmartWalletProfileEntity hot3 = walletProfile("0xCCC");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(hot1, hot2, hot3));

        service.refreshSubscriptions();

        ArgumentCaptor<List<String>> walletCaptor = ArgumentCaptor.forClass(List.class);
        verify(wsClient, times(1)).subscribeUsers(walletCaptor.capture());
        assertEquals(1, walletCaptor.getValue().size());
        // 第二、三轮各推一个
        service.refreshSubscriptions();
        service.refreshSubscriptions();
        verify(wsClient, times(3)).subscribeUsers(any());
    }

    @Test
    void refresh_shouldSwallowMetadataExceptionAndCountFailure() {
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(tradeEvent("bad-market")));
        when(marketMetadataService.getByConditionId("bad-market"))
                .thenThrow(new IllegalStateException("metadata-not-cached"));

        service.refreshSubscriptions();
        // 未抛出，subscribeMarkets 没被调用（因为没拿到 tokenId）
        verify(wsClient, never()).subscribeMarkets(any());
        assertEquals(1L, service.getStats().getTokenLookupFailures());
    }

    @Test
    void resetSubscriptionState_shouldAllowFullRepush() {
        SmartWalletProfileEntity hot1 = walletProfile("0xAAA");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(hot1));

        service.refreshSubscriptions();
        verify(wsClient, times(1)).subscribeUsers(any());

        service.resetSubscriptionState();
        service.refreshSubscriptions();
        // 重置后第二次又推一遍
        verify(wsClient, times(2)).subscribeUsers(any());
    }

    @Test
    void getStats_shouldReportCumulativeCounters() {
        SmartWalletProfileEntity hot1 = walletProfile("0xAAA");
        SmartWalletProfileEntity hot2 = walletProfile("0xBBB");
        when(profileStoreService.listExecutableWatchlist()).thenReturn(List.of(hot1, hot2));
        when(tradeEventMapper.selectList(any())).thenReturn(List.of(tradeEvent("market-1")));
        when(marketMetadataService.getByConditionId(anyString()))
                .thenReturn(metadata(Map.of("YES", "token-1A", "NO", "token-1B")));

        service.refreshSubscriptions();

        PolymarketWatchlistSubscriptionService.SubscriptionStats stats = service.getStats();
        assertTrue(stats.isEnabled());
        assertEquals(1L, stats.getRefreshCycles());
        assertEquals(2, stats.getSubscribedWalletCount());
        assertEquals(2, stats.getSubscribedTokenCount());
        assertEquals(2L, stats.getNewWalletsSubscribedTotal());
        assertEquals(2L, stats.getNewMarketsSubscribedTotal());
    }

    @Test
    void refresh_shouldSkipNullWalletsAndBlankMarketIds() {
        SmartWalletProfileEntity hotValid = walletProfile("0xAAA");
        SmartWalletProfileEntity hotNullAddr = walletProfile(null);
        SmartWalletProfileEntity hotBlankAddr = walletProfile("   ");
        when(profileStoreService.listExecutableWatchlist())
                .thenReturn(java.util.Arrays.asList(hotValid, null, hotNullAddr, hotBlankAddr));

        SmartWalletTradeEventEntity evGood = tradeEvent("market-1");
        SmartWalletTradeEventEntity evBlank = tradeEvent("");
        SmartWalletTradeEventEntity evNullMarket = tradeEvent(null);
        when(tradeEventMapper.selectList(any()))
                .thenReturn(java.util.Arrays.asList(evGood, null, evBlank, evNullMarket));
        when(marketMetadataService.getByConditionId("market-1"))
                .thenReturn(metadata(Map.of("YES", "token-1A")));

        service.refreshSubscriptions();

        ArgumentCaptor<List<String>> walletCaptor = ArgumentCaptor.forClass(List.class);
        verify(wsClient, atLeastOnce()).subscribeUsers(walletCaptor.capture());
        assertEquals(1, walletCaptor.getValue().size());
        assertEquals("0xaaa", walletCaptor.getValue().get(0));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private SmartWalletProfileEntity walletProfile(String address) {
        SmartWalletProfileEntity p = new SmartWalletProfileEntity();
        p.setWalletAddress(address);
        return p;
    }

    private SmartWalletTradeEventEntity tradeEvent(String marketId) {
        SmartWalletTradeEventEntity ev = new SmartWalletTradeEventEntity();
        ev.setMarketId(marketId);
        ev.setOpenedAt(new Date());
        return ev;
    }

    private PolymarketApiClient.MarketMetadata metadata(Map<String, String> outcomeTokenIds) {
        return PolymarketApiClient.MarketMetadata.builder()
                .outcomeTokenIds(new LinkedHashMap<>(outcomeTokenIds))
                .build();
    }
}
