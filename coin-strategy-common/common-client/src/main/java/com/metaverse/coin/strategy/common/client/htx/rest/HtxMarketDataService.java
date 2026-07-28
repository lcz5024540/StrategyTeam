package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.TypeReference;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTX Spot market-data endpoints.
 * <p>
 * Public API, no signing required. Only the endpoints that {@link com.metaverse.coin.strategy.common.client.htx.HtxApiService}
 * actually needs are exposed — additional endpoints can be added on-demand.
 */
@RequiredArgsConstructor
public class HtxMarketDataService {

    private final HtxRestClient rest;

    /**
     * Fetch order book snapshot.
     *
     * @param symbol e.g. {@code btcusdt}
     * @param depth  1/5/10/20 — HTX only accepts these preset depths for step0.
     * @return depth envelope; caller must check {@link HtxRestResponse#ok()} before {@link HtxRestResponse#payload()}.
     */
    public HtxRestResponse<HtxDepth> depth(String symbol, int depth) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("depth", String.valueOf(depth));
        params.put("type", "step0");
        return rest.publicGet("/market/depth", params, new TypeReference<HtxRestResponse<HtxDepth>>() {});
    }
}
