package com.metaverse.coin.strategy.job.smartwallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketSnapshotMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import com.metaverse.coin.strategy.common.service.smartwallet.config.SmartWalletProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketSnapshotCollectorServiceTest {

    @Mock
    private SmartWalletMarketSnapshotMapper marketSnapshotMapper;
    @Mock
    private SmartWalletTradeEventMapper tradeEventMapper;
    @Mock
    private PolymarketApiClient polymarketApiClient;
    @Mock
    private PolymarketMarketDataStreamService marketDataStreamService;
    @Mock
    private PolymarketMarketMetadataService marketMetadataService;
    @Mock
    private PolymarketMarketDataCacheService marketDataCacheService;

    private MarketSnapshotCollectorService service;
    private SmartWalletProperties smartWalletProperties;

    @BeforeEach
    void setUp() {
        smartWalletProperties = new SmartWalletProperties();
        service = new MarketSnapshotCollectorService(
                marketSnapshotMapper,
                tradeEventMapper,
                polymarketApiClient,
                marketDataStreamService,
                marketMetadataService,
                marketDataCacheService,
                smartWalletProperties
        );
        lenient().when(marketSnapshotMapper.selectOne(any())).thenReturn(null);
    }

    @Test
    void shouldPreferWsSnapshotOverRestOrderBook() {
        when(marketMetadataService.getByConditionId("market-1")).thenReturn(metadata("token-yes"));
        when(marketDataStreamService.getSnapshot("token-yes")).thenReturn(wsSnapshot(
                orderBook("token-yes", "0.44", "0.46"),
                "0.45"
        ));
        when(polymarketApiClient.fetchMarketTrades(eq("market-1"), eq(50), any()))
                .thenReturn(List.of(trade("0.43", "5")));

        SmartWalletMarketSnapshotEntity snapshot = service.collectSnapshot("market-1", "YES");

        assertEquals(new BigDecimal("0.45"), snapshot.getMidPrice());
        assertEquals(new BigDecimal("0.45"), snapshot.getLastTradePrice());
        verify(polymarketApiClient, never()).fetchOrderBook("token-yes");
    }

    @Test
    void shouldFallbackToRestOrderBookWhenWsSnapshotMissing() {
        when(marketMetadataService.getByConditionId("market-1")).thenReturn(metadata("token-yes"));
        when(marketDataStreamService.getSnapshot("token-yes")).thenReturn(null);
        when(marketDataCacheService.getOrderBook("token-yes"))
                .thenReturn(orderBook("token-yes", "0.40", "0.50"));
        when(polymarketApiClient.fetchMarketTrades(eq("market-1"), eq(50), any()))
                .thenReturn(List.of(trade("0.41", "7")));

        service.collectSnapshot("market-1", "YES");

        verify(marketDataCacheService).getOrderBook("token-yes");
        ArgumentCaptor<SmartWalletMarketSnapshotEntity> captor = ArgumentCaptor.forClass(SmartWalletMarketSnapshotEntity.class);
        verify(marketSnapshotMapper).insert(captor.capture());
        assertEquals(new BigDecimal("0.45"), captor.getValue().getMidPrice());
        assertEquals(new BigDecimal("0.41"), captor.getValue().getLastTradePrice());
    }

    // ─────────────────────────────────────────────────────────────────────
    // F-P1-7: findFreshSnapshot — hot-path WS optimization unit tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void findFreshSnapshot_switchOff_returnsDbResult() {
        // 默认配置 enableWsHotPathSnapshot=false：必须走 DB 路径，不查 WS
        SmartWalletMarketSnapshotEntity dbEntity = new SmartWalletMarketSnapshotEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setMidPrice(new BigDecimal("0.50"))
                .setSnapshotAt(new Date());
        when(marketSnapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dbEntity);

        SmartWalletMarketSnapshotEntity result = service.findFreshSnapshot("market-1", "YES");

        assertSame(dbEntity, result);
        // 开关 OFF 时不调用 hot-path 计数器
        assertEquals(0L, service.getHotPathWsHits());
        assertEquals(0L, service.getHotPathDbFallbacks());
        // 开关 OFF 时不查 marketMetadata / marketDataStream
        verifyNoInteractions(marketMetadataService);
        verifyNoInteractions(marketDataStreamService);
    }

    @Test
    void findFreshSnapshot_switchOn_wsHit_returnsWsSynthesizedSnapshot() {
        smartWalletProperties.getPolymarket().setEnableWsHotPathSnapshot(true);
        smartWalletProperties.getPolymarket().setWsHotPathSnapshotMaxAgeMs(5_000L);

        when(marketMetadataService.getByConditionId("market-1")).thenReturn(metadata("token-yes"));
        // WS fresh: updatedAt 是当前时间，年龄 < 5s
        when(marketDataStreamService.getSnapshot("token-yes"))
                .thenReturn(wsSnapshot(orderBook("token-yes", "0.49", "0.51"), "0.50"));

        SmartWalletMarketSnapshotEntity result = service.findFreshSnapshot("market-1", "YES");

        assertNotNull(result);
        // F-P1-7 透传 WS orderBook 的 midpoint (本测试中由 orderBook helper 设置为 0.45)
        assertEquals(new BigDecimal("0.45"), result.getMidPrice());
        assertEquals("market-1", result.getMarketId());
        assertEquals("YES", result.getOutcome());
        // 命中计数器
        assertEquals(1L, service.getHotPathWsHits());
        assertEquals(0L, service.getHotPathDbFallbacks());
        // 命中 WS 时不应回查 DB (findLatestSnapshotAtOrBefore via selectOne)
        verify(marketSnapshotMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void findFreshSnapshot_switchOn_wsStale_fallbackToDb() {
        smartWalletProperties.getPolymarket().setEnableWsHotPathSnapshot(true);
        smartWalletProperties.getPolymarket().setWsHotPathSnapshotMaxAgeMs(5_000L);

        when(marketMetadataService.getByConditionId("market-1")).thenReturn(metadata("token-yes"));
        // WS stale: updatedAt 是 60s 前，远超 5s max age
        PolymarketMarketDataStreamService.MarketDataSnapshot staleSnapshot =
                PolymarketMarketDataStreamService.MarketDataSnapshot.builder()
                        .tokenId("token-yes")
                        .orderBook(orderBook("token-yes", "0.49", "0.51"))
                        .lastTradePrice(new BigDecimal("0.50"))
                        .updatedAt(new Date(System.currentTimeMillis() - 60_000L))
                        .build();
        when(marketDataStreamService.getSnapshot("token-yes")).thenReturn(staleSnapshot);

        SmartWalletMarketSnapshotEntity dbEntity = new SmartWalletMarketSnapshotEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setMidPrice(new BigDecimal("0.40"))
                .setSnapshotAt(new Date());
        when(marketSnapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dbEntity);

        SmartWalletMarketSnapshotEntity result = service.findFreshSnapshot("market-1", "YES");

        assertSame(dbEntity, result);
        // stale → 回落到 DB，计数 fallback
        assertEquals(0L, service.getHotPathWsHits());
        assertEquals(1L, service.getHotPathDbFallbacks());
    }

    @Test
    void findFreshSnapshot_switchOn_metadataMissing_fallbackToDb() {
        smartWalletProperties.getPolymarket().setEnableWsHotPathSnapshot(true);

        // marketMetadata 缺失 (上游元数据未同步) → 拿不到 tokenId → 走 DB
        when(marketMetadataService.getByConditionId("market-1")).thenReturn(null);

        SmartWalletMarketSnapshotEntity dbEntity = new SmartWalletMarketSnapshotEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setMidPrice(new BigDecimal("0.42"))
                .setSnapshotAt(new Date());
        when(marketSnapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dbEntity);

        SmartWalletMarketSnapshotEntity result = service.findFreshSnapshot("market-1", "YES");

        assertSame(dbEntity, result);
        // 元数据缺失 fallback 到 DB，计数 fallback
        assertEquals(0L, service.getHotPathWsHits());
        assertEquals(1L, service.getHotPathDbFallbacks());
        // 由于元数据 null 就早返回，不调 marketDataStreamService
        verify(marketDataStreamService, never()).getSnapshot(anyString());
    }

    @Test
    void findFreshSnapshot_switchOn_wsThrows_fallbackToDb() {
        smartWalletProperties.getPolymarket().setEnableWsHotPathSnapshot(true);

        when(marketMetadataService.getByConditionId("market-1")).thenReturn(metadata("token-yes"));
        // WS getSnapshot 抛异常 (网络抖动 / 反序列化失败 / GC pause 等) — F-P1-7 设计要求静默回落
        when(marketDataStreamService.getSnapshot("token-yes"))
                .thenThrow(new RuntimeException("synthetic WS failure"));

        SmartWalletMarketSnapshotEntity dbEntity = new SmartWalletMarketSnapshotEntity()
                .setMarketId("market-1")
                .setOutcome("YES")
                .setMidPrice(new BigDecimal("0.38"))
                .setSnapshotAt(new Date());
        when(marketSnapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(dbEntity);

        SmartWalletMarketSnapshotEntity result = service.findFreshSnapshot("market-1", "YES");

        // 关键断言：异常被吞，不抛出，回落到 DB
        assertSame(dbEntity, result);
        assertEquals(0L, service.getHotPathWsHits());
        assertEquals(1L, service.getHotPathDbFallbacks());
    }

    @Test
    void findFreshSnapshot_blankMarketId_returnsNullWithoutTouchingAnything() {
        SmartWalletMarketSnapshotEntity result = service.findFreshSnapshot("  ", "YES");
        assertNull(result);
        verifyNoInteractions(marketSnapshotMapper);
        verifyNoInteractions(marketMetadataService);
        verifyNoInteractions(marketDataStreamService);
    }

    private PolymarketApiClient.MarketMetadata metadata(String tokenId) {
        return PolymarketApiClient.MarketMetadata.builder()
                .conditionId("market-1")
                .outcomeTokenIds(Map.of("YES", tokenId))
                .build();
    }

    private PolymarketMarketDataStreamService.MarketDataSnapshot wsSnapshot(PolymarketApiClient.OrderBook orderBook,
                                                                            String lastTradePrice) {
        return PolymarketMarketDataStreamService.MarketDataSnapshot.builder()
                .tokenId(orderBook.getTokenId())
                .orderBook(orderBook)
                .lastTradePrice(new BigDecimal(lastTradePrice))
                .updatedAt(new java.util.Date())
                .build();
    }

    private PolymarketApiClient.OrderBook orderBook(String tokenId, String bid, String ask) {
        return PolymarketApiClient.OrderBook.builder()
                .tokenId(tokenId)
                .midpoint(new BigDecimal("0.45"))
                .spread(new BigDecimal("0.10"))
                .bids(List.of(level(bid, "100")))
                .asks(List.of(level(ask, "100")))
                .build();
    }

    private PolymarketApiClient.OrderBookLevel level(String price, String size) {
        return PolymarketApiClient.OrderBookLevel.builder()
                .price(new BigDecimal(price))
                .size(new BigDecimal(size))
                .build();
    }

    private PolymarketApiClient.MarketTrade trade(String price, String size) {
        return PolymarketApiClient.MarketTrade.builder()
                .price(new BigDecimal(price))
                .size(new BigDecimal(size))
                .side("BUY")
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
