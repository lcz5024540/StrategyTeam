package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;

import java.util.Map;

public class SunwkOrderService extends SunwkService {

    public SunwkOrderService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public String getOrder(String contractCode, String orderId, String clientOrderId) {
        return execute(sunwkApi.getOrder(contractCode, orderId, clientOrderId), "Failed to get order");
    }

    public String placeOrder(Map<String, Object> req) {
        return execute(sunwkApi.placeOrder(req), "Failed to place order");
    }

    public String cancelOrder(Map<String, Object> req) {
        return execute(sunwkApi.cancelOrder(req), "Failed to cancel order");
    }
}
