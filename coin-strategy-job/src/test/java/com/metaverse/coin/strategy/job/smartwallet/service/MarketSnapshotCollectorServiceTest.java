package com.metaverse.coin.strategy.job.smartwallet.service;

import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletMarketSnapshotMapper;
import com.metaverse.coin.strategy.common.dao.mapper.SmartWalletTradeEventMapper;
import com.metaverse.coin.strategy.common.model.arbitrage.entity.SmartWalletMarketSnapshotEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private MarketSnapshotCollectorService service;

    @BeforeEach
    void setUp() {
        service = new MarketSnapshotCollectorService(
                marketSnapshotMapper,
                tradeEventMapper,
                polymarketApiClient,
                marketDataStreamService,
                marketMetadataService
        );
        when(marketSnapshotMapper.selectOne(any())).thenReturn(null);
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
        when(polymarketApiClient.fetchOrderBook("token-yes")).thenReturn(orderBook("token-yes", "0.40", "0.50"));
        when(polymarketApiClient.fetchMarketTrades(eq("market-1"), eq(50), any()))
                .thenReturn(List.of(trade("0.41", "7")));

        service.collectSnapshot("market-1", "YES");

        verify(polymarketApiClient).fetchOrderBook("token-yes");
        ArgumentCaptor<SmartWalletMarketSnapshotEntity> captor = ArgumentCaptor.forClass(SmartWalletMarketSnapshotEntity.class);
        verify(marketSnapshotMapper).insert(captor.capture());
        assertEquals(new BigDecimal("0.45"), captor.getValue().getMidPrice());
        assertEquals(new BigDecimal("0.41"), captor.getValue().getLastTradePrice());
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
