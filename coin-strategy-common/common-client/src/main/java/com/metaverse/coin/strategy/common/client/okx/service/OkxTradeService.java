package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.PlaceOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.PlaceOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.OrderType;
import com.metaverse.coin.strategy.common.model.okx.enums.PositionsSide;
import com.metaverse.coin.strategy.common.model.okx.enums.Side;
import com.metaverse.coin.strategy.common.model.okx.enums.ws.TdMode;

import java.math.BigDecimal;
import java.util.HashMap;
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

    public OkxRestResponse<Object> closePosition(String instId, String mgnMode, String posSide) {
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

    public OkxRestResponse<Map<String, Object>> getOrder(String instId, String ordId, String clOrdId) {
        return execute(okxApi.getOrder(instId, ordId, clOrdId), "Get order failed");
    }

    public OkxRestResponse<Map<String, Object>> getOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getOrdersHistory(queryMap), "Get orders history failed");
    }

    public OkxRestResponse<Map<String, Object>> getOrdersPending(Map<String, Object> queryMap) {
        return execute(okxApi.getOrdersPending(queryMap), "Get orders pending failed");
    }
}
