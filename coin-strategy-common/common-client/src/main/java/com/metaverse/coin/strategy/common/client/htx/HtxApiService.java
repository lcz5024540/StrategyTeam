package com.metaverse.coin.strategy.common.client.htx;

import com.metaverse.coin.strategy.common.client.htx.rest.HtxAccountService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxMarketDataService;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxRestClient;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxSpotService;
import com.metaverse.coin.strategy.common.client.htx.ws.HtxWebSocketClient;
import lombok.Getter;

/**
 * Facade for HTX (Huobi) Spot API access.
 * <p>
 * Mirrors the structure of {@link com.metaverse.coin.strategy.common.client.binance.BinanceApiService}
 * and {@link com.metaverse.coin.strategy.common.client.okx.OkxApiService}: one class aggregates REST +
 * WebSocket sub-services, and downstream code depends only on this facade.
 * <p>
 * Wiring lives in {@code coin-strategy-job}'s {@code HtxConfig}; this class is deliberately
 * Spring-agnostic so it is easy to construct in tests.
 */
@Getter
public class HtxApiService {

    private final HtxRestClient rest;
    private final HtxSpotService spotService;
    private final HtxMarketDataService marketDataService;
    private final HtxAccountService accountService;
    private final HtxWebSocketClient webSocketClient;

    public HtxApiService(String accessKey, String secretKey) {
        this(accessKey, secretKey, "https://api.huobi.pro", "wss://api.huobi.pro/ws");
    }

    public HtxApiService(String accessKey, String secretKey, String baseUrl, String wsUrl) {
        this.rest = new HtxRestClient(accessKey, secretKey, baseUrl);
        this.spotService = new HtxSpotService(rest);
        this.marketDataService = new HtxMarketDataService(rest);
        this.accountService = new HtxAccountService(rest);
        this.webSocketClient = new HtxWebSocketClient(wsUrl);
    }

    /**
     * Test seam: inject a pre-built {@link HtxRestClient} (e.g. wired to MockWebServer).
     */
    public HtxApiService(HtxRestClient rest, HtxWebSocketClient ws) {
        this.rest = rest;
        this.spotService = new HtxSpotService(rest);
        this.marketDataService = new HtxMarketDataService(rest);
        this.accountService = new HtxAccountService(rest);
        this.webSocketClient = ws;
    }
}
