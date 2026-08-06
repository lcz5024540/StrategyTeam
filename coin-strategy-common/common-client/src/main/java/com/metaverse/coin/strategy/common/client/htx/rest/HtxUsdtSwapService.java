package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.TypeReference;
import com.metaverse.coin.strategy.common.client.htx.model.HtxDepth;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapAvailableLeverage;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapContractInfo;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapLeverageResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrder;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapOrderResult;
import com.metaverse.coin.strategy.common.client.htx.model.HtxSwapPosition;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal HTX isolated USDT-margined perpetual swap REST client. */
@RequiredArgsConstructor
public class HtxUsdtSwapService {

    private final HtxRestClient rest;

    public HtxRestResponse<List<HtxSwapContractInfo>> contractInfo(String contractCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("business_type", "swap");
        return rest.publicGet("/linear-swap-api/v1/swap_contract_info", params,
                new TypeReference<HtxRestResponse<List<HtxSwapContractInfo>>>() {});
    }

    public HtxRestResponse<HtxDepth> depth(String contractCode, int depth) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contract_code", contractCode);
        params.put("type", "step" + depth);
        HtxRestResponse<HtxDepth> response = rest.publicGet("/linear-swap-ex/market/depth", params,
                new TypeReference<HtxRestResponse<HtxDepth>>() {});
        if (response.getData() == null && response.getTick() != null) {
            response.setData(response.getTick());
        }
        return response;
    }

    public HtxRestResponse<List<HtxSwapAccount>> accountInfo(String contractCode) {
        return rest.signedPost("/linear-swap-api/v1/swap_account_info", Collections.emptyMap(),
                singletonBody("contract_code", contractCode),
                new TypeReference<HtxRestResponse<List<HtxSwapAccount>>>() {});
    }

    public HtxRestResponse<List<HtxSwapPosition>> positionInfo(String contractCode) {
        return rest.signedPost("/linear-swap-api/v1/swap_position_info", Collections.emptyMap(),
                singletonBody("contract_code", contractCode),
                new TypeReference<HtxRestResponse<List<HtxSwapPosition>>>() {});
    }

    public HtxRestResponse<List<HtxSwapAvailableLeverage>> availableLeverage(String contractCode) {
        return rest.signedPost("/linear-swap-api/v1/swap_available_level_rate", Collections.emptyMap(),
                singletonBody("contract_code", contractCode),
                new TypeReference<HtxRestResponse<List<HtxSwapAvailableLeverage>>>() {});
    }

    public HtxRestResponse<HtxSwapLeverageResult> switchLeverage(String contractCode, int leverage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_code", contractCode);
        body.put("lever_rate", leverage);
        return rest.signedPost("/linear-swap-api/v1/swap_switch_lever_rate", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<HtxSwapLeverageResult>>() {});
    }

    public HtxRestResponse<HtxSwapOrderResult> placeOrder(String contractCode,
                                                           long clientOrderId,
                                                           long volume,
                                                           String direction,
                                                           String offset,
                                                           int leverage,
                                                           String price,
                                                           String orderPriceType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_code", contractCode);
        body.put("client_order_id", clientOrderId);
        body.put("volume", volume);
        body.put("direction", direction);
        body.put("offset", offset);
        body.put("lever_rate", leverage);
        body.put("order_price_type", orderPriceType);
        if (price != null && !price.isEmpty()) {
            body.put("price", price);
        }
        return rest.signedPost("/linear-swap-api/v1/swap_order", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<HtxSwapOrderResult>>() {});
    }

    public HtxRestResponse<Map<String, Object>> cancelOrder(String contractCode, long orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_code", contractCode);
        body.put("order_id", Long.toString(orderId));
        return rest.signedPost("/linear-swap-api/v1/swap_cancel", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<Map<String, Object>>>() {});
    }

    public HtxRestResponse<List<HtxSwapOrder>> queryOrder(String contractCode, long orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contract_code", contractCode);
        body.put("order_id", Long.toString(orderId));
        return rest.signedPost("/linear-swap-api/v1/swap_order_info", Collections.emptyMap(), body,
                new TypeReference<HtxRestResponse<List<HtxSwapOrder>>>() {});
    }

    private static Map<String, Object> singletonBody(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, value);
        return body;
    }
}
