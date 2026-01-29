package com.metaverse.coin.strategy.common.client.sunwk.service;

import com.metaverse.coin.strategy.common.client.sunwk.SunwkApi;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkOrder;
import com.metaverse.coin.strategy.common.model.sunwk.entity.SunwkPlanOrder;

import java.util.List;
import java.util.Map;

public class SunwkOrderService extends SunwkService {

    public SunwkOrderService(SunwkApi sunwkApi) {
        super(sunwkApi);
    }

    public SunwkOrder getOrder(String contractCode, String orderId, String clientOrderId) {
        return executeData(sunwkApi.getOrder(contractCode, orderId, clientOrderId), "Failed to get order");
    }

    public List<SunwkOrder> getOrderHistory(Map<String, Object> queryMap) {
        return executeData(sunwkApi.getOrderHistory(queryMap), "Failed to get order history");
    }

    public SunwkOrder placeOrder(Map<String, Object> req) {
        return executeData(sunwkApi.placeOrder(req), "Failed to place order");
    }

    public SunwkOrder cancelOrder(Map<String, Object> req) {
        return executeData(sunwkApi.cancelOrder(req), "Failed to cancel order");
    }

    public List<SunwkOrder> getOpenOrders(String contractCode, Integer index, Integer limit) {
        return executeData(sunwkApi.getOpenOrders(contractCode, index, limit), "Failed to get open orders");
    }

    public SunwkOrder cancelAllOrders(Map<String, Object> req) {
        return executeData(sunwkApi.cancelAllOrders(req), "Failed to cancel all orders");
    }

    public List<SunwkOrder> batchPlaceOrders(Map<String, Object> req) {
        return executeData(sunwkApi.batchPlaceOrders(req), "Failed to batch place orders");
    }

    public List<SunwkOrder> batchCancelOrders(Map<String, Object> req) {
        return executeData(sunwkApi.batchCancelOrders(req), "Failed to batch cancel orders");
    }

    // --- Plan Order ---

    public SunwkPlanOrder placePlanOrder(Map<String, Object> req) {
        return executeData(sunwkApi.placePlanOrder(req), "Failed to place plan order");
    }

    public SunwkPlanOrder cancelPlanOrder(Map<String, Object> req) {
        return executeData(sunwkApi.cancelPlanOrder(req), "Failed to cancel plan order");
    }

    public SunwkPlanOrder getPlanOrder(String contractCode, String orderId) {
        return executeData(sunwkApi.getPlanOrder(contractCode, orderId), "Failed to get plan order");
    }

    public List<SunwkPlanOrder> getPlanOpenOrders(String contractCode, Integer index, Integer limit) {
        return executeData(sunwkApi.getPlanOpenOrders(contractCode, index, limit), "Failed to get plan open orders");
    }

    public List<SunwkPlanOrder> getPlanOrderHistory(Map<String, Object> queryMap) {
        return executeData(sunwkApi.getPlanOrderHistory(queryMap), "Failed to get plan order history");
    }
}
