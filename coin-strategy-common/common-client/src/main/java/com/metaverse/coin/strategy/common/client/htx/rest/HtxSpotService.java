package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.TypeReference;
import com.metaverse.coin.strategy.common.client.htx.model.HtxOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTX Spot trading endpoints (private).
 * <p>
 * Order side conventions (mirroring HTX's {@code type} field):
 * <ul>
 *   <li>{@code buy-limit} — quantity in base asset, price required</li>
 *   <li>{@code sell-limit} — quantity in base asset, price required</li>
 *   <li>{@code buy-market} — quantity is quote-asset spend (e.g. USDT amount)</li>
 *   <li>{@code sell-market} — quantity in base asset</li>
 * </ul>
 */
@RequiredArgsConstructor
public class HtxSpotService {

    private final HtxRestClient rest;

    /**
     * Place a spot order.
     * <p>
     * HTX returns the order id as a JSON <b>string</b>, not a number (docs example:
     * {@code {"status":"ok","data":"59378"}}). We keep the wire type as {@link String}
     * and let callers parse to long — otherwise fastjson2's lenient conversion can silently
     * yield null for large ids or ids with non-numeric prefixes (e.g. high-frequency
     * account order-id variants).
     *
     * @param accountId spot account id from {@link HtxAccountService#listAccounts()}
     * @param symbol    e.g. btcusdt
     * @param type      buy-limit / sell-limit / buy-market / sell-market / buy-limit-maker / sell-limit-maker / ioc / fok variants
     * @param amount    order amount (see class-level docs for semantics)
     * @param price     limit price; may be null for market orders
     * @param clientOrderId optional client-side idempotency key (<= 64 chars). Passing the same id twice
     *                      returns the original order — HTX handles idempotency server-side.
     */
    public HtxRestResponse<String> placeOrder(String accountId,
                                              String symbol,
                                              String type,
                                              String amount,
                                              String price,
                                              String clientOrderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account-id", accountId);
        body.put("symbol", symbol);
        body.put("type", type);
        body.put("amount", amount);
        if (price != null && !price.isEmpty()) {
            body.put("price", price);
        }
        if (clientOrderId != null && !clientOrderId.isEmpty()) {
            body.put("client-order-id", clientOrderId);
        }
        return rest.signedPost("/v1/order/orders/place", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<String>>() {});
    }

    /**
     * Cancel a spot order by HTX order id. Returned data field is the same order id (string).
     */
    public HtxRestResponse<String> cancelOrder(long orderId) {
        return rest.signedPost("/v1/order/orders/" + orderId + "/submitcancel", Collections.emptyMap(), null,
                new TypeReference<HtxRestResponse<String>>() {});
    }

    /**
     * Query one order by HTX order id.
     */
    public HtxRestResponse<HtxOrder> queryOrder(long orderId) {
        return rest.signedGet("/v1/order/orders/" + orderId, Collections.emptyMap(),
                new TypeReference<HtxRestResponse<HtxOrder>>() {});
    }

    /**
     * Query one order by client-order-id.
     */
    public HtxRestResponse<HtxOrder> queryOrderByClientId(String clientOrderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clientOrderId", clientOrderId);
        return rest.signedGet("/v1/order/orders/getClientOrder", params,
                new TypeReference<HtxRestResponse<HtxOrder>>() {});
    }
}
