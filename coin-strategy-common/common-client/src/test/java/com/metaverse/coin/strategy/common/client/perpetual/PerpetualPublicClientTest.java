package com.metaverse.coin.strategy.common.client.perpetual;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerpetualPublicClientTest {
    private final MockWebServer server = new MockWebServer();

    @AfterEach
    void closeServer() throws IOException {
        server.close();
    }

    @Test
    void parsesBinanceContractsFundingAndBook() {
        enqueue("{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"contractType\":\"PERPETUAL\","
                + "\"quoteAsset\":\"USDT\",\"status\":\"TRADING\",\"filters\":["
                + "{\"filterType\":\"PRICE_FILTER\",\"tickSize\":\"0.1\"},"
                + "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.001\"},"
                + "{\"filterType\":\"MIN_NOTIONAL\",\"notional\":\"5\"}]}]}");
        enqueue("{\"lastFundingRate\":\"0.0001\",\"nextFundingTime\":1787184000000,"
                + "\"markPrice\":\"100\",\"indexPrice\":\"99.9\"}");
        enqueue("{\"bids\":[[\"99\",\"2\"]],\"asks\":[[\"101\",\"3\"]]}");

        PerpetualMarketDataClient client = client(PerpetualPublicClient.Venue.BINANCE);

        assertEquals("BTCUSDT", client.contracts().get(0).canonicalSymbol());
        assertEquals("0.0001", client.funding("BTCUSDT").rawRate().toPlainString());
        PerpetualMarketDataClient.OrderBook book = client.orderBook("BTCUSDT", 5);
        assertEquals("303", book.askNotional().toPlainString());
        assertEquals("101", book.askLevels().get(0).price().toPlainString());
    }

    @Test
    void parsesOkxContractsFundingAndBook() {
        enqueue("{\"data\":[{\"instId\":\"BTC-USDT-SWAP\",\"last\":\"100\"}]}");
        enqueue("{\"data\":[{\"instId\":\"BTC-USDT-SWAP\",\"settleCcy\":\"USDT\","
                + "\"state\":\"live\",\"ctValCcy\":\"BTC\",\"tickSz\":\"0.1\","
                + "\"lotSz\":\"0.01\",\"minSz\":\"1\",\"ctVal\":\"0.01\"}]}");
        enqueue("{\"data\":[{\"fundingRate\":\"-0.0002\",\"fundingTime\":1787155200000,"
                + "\"nextFundingTime\":1787184000000}]}");
        enqueue("{\"data\":[{\"markPx\":\"100\"}]}");
        enqueue("{\"data\":[{\"idxPx\":\"99.8\"}]}");
        enqueue("{\"data\":[{\"bids\":[[\"99\",\"2\"]],"
                + "\"asks\":[[\"101\",\"3\"]]}]}");

        PerpetualMarketDataClient client = client(PerpetualPublicClient.Venue.OKX);

        assertEquals("BTCUSDT", client.contracts().get(0).canonicalSymbol());
        assertEquals(8, client.funding("BTCUSDT").intervalHours());
        PerpetualMarketDataClient.OrderBook book = client.orderBook("BTCUSDT", 5);
        assertEquals("1.98", book.bidNotional().toPlainString());
        assertEquals("1.98", book.bidLevels().get(0).notional().toPlainString());
    }

    @Test
    void parsesHtxContractsFundingAndBook() {
        enqueue("{\"ticks\":[{\"contract_code\":\"BTC-USDT\",\"close\":\"100\"}]}");
        enqueue("{\"data\":[{\"contract_code\":\"BTC-USDT\",\"contract_status\":1,"
                + "\"settlement_asset\":\"USDT\",\"symbol\":\"BTC\","
                + "\"price_tick\":\"0.1\",\"contract_size\":\"0.01\"}]}");
        enqueue("{\"data\":{\"funding_rate\":\"-0.0003\","
                + "\"next_funding_time\":1787184000000}}");
        enqueue("{\"tick\":{\"close\":\"100\"}}");
        enqueue("{\"data\":[{\"index_price\":\"99.7\"}]}");
        enqueue("{\"tick\":{\"bids\":[[99,2]],\"asks\":[[101,3]]}}");

        PerpetualMarketDataClient client = client(PerpetualPublicClient.Venue.HTX);

        assertEquals("BTC-USDT", client.contracts().get(0).exchangeSymbol());
        assertEquals("99.7", client.funding("BTCUSDT").indexPrice().toPlainString());
        assertEquals("3.03", client.orderBook("BTCUSDT", 5).askNotional().toPlainString());
    }

    @Test
    void parsesBitgetContractsFundingAndBook() {
        enqueue("{\"data\":[{\"symbol\":\"BTCUSDT\",\"symbolStatus\":\"normal\","
                + "\"minTradeUSDT\":\"5\",\"volumePlace\":3,\"pricePlace\":1}]}");
        enqueue("{\"data\":[{\"fundingRate\":\"0.0004\",\"fundingRateInterval\":8,"
                + "\"nextUpdate\":1787184000000}]}");
        enqueue("{\"data\":[{\"markPrice\":\"100\",\"indexPrice\":\"99.6\"}]}");
        enqueue("{\"data\":{\"bids\":[[\"99\",\"2\"]],"
                + "\"asks\":[[\"101\",\"3\"]]}}");

        PerpetualMarketDataClient client = client(PerpetualPublicClient.Venue.BITGET);

        assertEquals(3, client.contracts().get(0).quantityScale());
        assertEquals("0.0004", client.funding("BTCUSDT").rawRate().toPlainString());
        assertEquals("198", client.orderBook("BTCUSDT", 5).bidNotional().toPlainString());
    }

    @Test
    void rejectsBitgetFundingWithoutSettlementTime() {
        enqueue("{\"data\":[{\"fundingRate\":\"0.0004\",\"fundingRateInterval\":8}]}");
        enqueue("{\"data\":[{\"markPrice\":\"100\",\"indexPrice\":\"99.6\"}]}");

        PerpetualMarketDataClient client = client(PerpetualPublicClient.Venue.BITGET);

        assertThrows(IllegalStateException.class, () -> client.funding("BTCUSDT"));
    }

    @Test
    void translatesTransportRateLimitToConnectorException() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate limited"));
        PerpetualMarketDataClient client = client(PerpetualPublicClient.Venue.BINANCE);

        assertThrows(PerpetualMarketDataClient.RateLimitException.class, client::contracts);
    }

    private PerpetualMarketDataClient client(PerpetualPublicClient.Venue venue) {
        return PerpetualMarketDataClient.forVenue(venue, server.url("/").toString());
    }

    private void enqueue(String json) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json").setBody(json));
    }
}
