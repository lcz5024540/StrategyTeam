package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo.OkxAlgoOrder;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.*;
import com.metaverse.coin.strategy.common.model.okx.enums.OrderType;
import com.metaverse.coin.strategy.common.model.okx.enums.PositionsSide;
import com.metaverse.coin.strategy.common.model.okx.enums.Side;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OkxTradeService extends OkxService {

    public OkxTradeService(OkxApi okxApi) {
        super(okxApi);
    }

    // Open Long
    public OkxRestResponse<PlaceOrderRsp> openLong(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        PlaceOrderReq req = PlaceOrderReq.builder()
                .instId(instId)
                .tdMode(tdMode)
                .side(Side.BUY)
                .posSide(PositionsSide.LONG)
                .ordType(price != null ? OrderType.LIMIT : OrderType.MARKET)
                .sz(size)
                .px(price)
                .build();
        return executeOrder(req);
    }

    // Open Short
    public OkxRestResponse<PlaceOrderRsp> openShort(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        PlaceOrderReq req = PlaceOrderReq.builder()
                .instId(instId)
                .tdMode(tdMode)
                .side(Side.SELL)
                .posSide(PositionsSide.SHORT)
                .ordType(price != null ? OrderType.LIMIT : OrderType.MARKET)
                .sz(size)
                .px(price)
                .build();
        return executeOrder(req);
    }

    // Close Long
    public OkxRestResponse<PlaceOrderRsp> closeLong(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        PlaceOrderReq req = PlaceOrderReq.builder()
                .instId(instId)
                .tdMode(tdMode)
                .side(Side.SELL)
                .posSide(PositionsSide.LONG)
                .ordType(price != null ? OrderType.LIMIT : OrderType.MARKET)
                .sz(size)
                .px(price)
                .build();
        return executeOrder(req);
    }

    // Close Short
    public OkxRestResponse<PlaceOrderRsp> closeShort(String instId, TdMode tdMode, BigDecimal size, BigDecimal price) {
        PlaceOrderReq req = PlaceOrderReq.builder()
                .instId(instId)
                .tdMode(tdMode)
                .side(Side.BUY)
                .posSide(PositionsSide.SHORT)
                .ordType(price != null ? OrderType.LIMIT : OrderType.MARKET)
                .sz(size)
                .px(price)
                .build();
        return executeOrder(req);
    }

    private OkxRestResponse<PlaceOrderRsp> executeOrder(PlaceOrderReq req) {
        return execute(okxApi.placeOrder(req), "Place order failed");
    }

    public OkxRestResponse<OkxClosePositionRsp> closePosition(String instId, String mgnMode, String posSide) {
        Map<String, Object> req = new HashMap<>();
        req.put("instId", instId);
        req.put("mgnMode", mgnMode);
        if (posSide != null) {
            req.put("posSide", posSide);
        }
        return execute(okxApi.closePosition(req), "Close position failed");
    }

    public OkxRestResponse<CancelOrderRsp> cancelOrder(CancelOrderReq req) {
        return execute(okxApi.cancelOrder(req), "Cancel order failed");
    }

    public OkxRestResponse<OkxOrder> getOrder(String instId, String ordId, String clOrdId) {
        return execute(okxApi.getOrder(instId, ordId, clOrdId), "Get order failed");
    }

    public OkxRestResponse<PlaceOrderRsp> batchOrders(List<PlaceOrderReq> req) {
        return execute(okxApi.batchOrders(req), "Batch orders failed");
    }

    public OkxRestResponse<CancelOrderRsp> cancelBatchOrders(List<CancelOrderReq> req) {
        return execute(okxApi.cancelBatchOrders(req), "Cancel batch orders failed");
    }

    public OkxRestResponse<AmendOrderRsp> amendOrder(AmendOrderReq req) {
        return execute(okxApi.amendOrder(req), "Amend order failed");
    }

    public OkxRestResponse<AmendOrderRsp> amendBatchOrders(List<AmendOrderReq> req) {
        return execute(okxApi.amendBatchOrders(req), "Amend batch orders failed");
    }

    public OkxRestResponse<Object> massCancel(MassCancelOrderReq req) {
        return execute(okxApi.massCancel(req), "Mass cancel failed");
    }

    public OkxRestResponse<CancelAllAfterRsp> cancelAllAfter(CancelAllAfterReq req) {
        return execute(okxApi.cancelAllAfter(req), "Cancel all after failed");
    }

    public OkxRestResponse<OkxOrder> getOrdersHistoryArchive(Map<String, Object> queryMap) {
        return execute(okxApi.getOrdersHistoryArchive(queryMap), "Get orders history archive failed");
    }

    public OkxRestResponse<OkxTransaction> getFills(Map<String, Object> queryMap) {
        return execute(okxApi.getFills(queryMap), "Get fills failed");
    }

    public OkxRestResponse<OkxTransaction> getFillsHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getFillsHistory(queryMap), "Get fills history failed");
    }

    public OkxRestResponse<OkxOrder> getOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getOrdersHistory(queryMap), "Get orders history failed");
    }

    public OkxRestResponse<OkxOrder> getOrdersPending(Map<String, Object> queryMap) {
        return execute(okxApi.getOrdersPending(queryMap), "Get orders pending failed");
    }

    public OkxRestResponse<OkxAlgoOrder> getAlgoOrdersPending(Map<String, Object> queryMap) {
        return execute(okxApi.getAlgoOrdersPending(queryMap), "Get algo orders pending failed");
    }
}
