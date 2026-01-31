package com.metaverse.coin.strategy.common.client.gate.service;

import io.gate.gateapi.ApiException;
import io.gate.gateapi.api.FuturesApi;
import io.gate.gateapi.api.SpotApi;
import io.gate.gateapi.models.FuturesOrder;
import io.gate.gateapi.models.Order;
import lombok.extern.slf4j.Slf4j;

/**
 * Gate.io Trade Service
 * <p>
 * Handles Order Placement and Cancellation for Spot and Futures.
 * </p>
 */
@Slf4j
public class GateTradeService extends GateService {

    private final SpotApi spotApi;
    private final FuturesApi futuresApi;

    public GateTradeService(SpotApi spotApi, FuturesApi futuresApi) {
        this.spotApi = spotApi;
        this.futuresApi = futuresApi;
    }

    /**
     * Place a Spot Order.
     *
     * @param order Order object containing details like currencyPair, side, amount, price, etc.
     * @return Created Order
     */
    public Order placeSpotOrder(Order order) {
        try {
            return spotApi.createOrder(order, null);
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }

    /**
     * Cancel a Spot Order.
     *
     * @param orderId      Order ID to cancel.
     * @param currencyPair Currency Pair (e.g., "BTC_USDT").
     * @return Cancelled Order
     */
    public Order cancelSpotOrder(String orderId, String currencyPair) {
        try {
            return spotApi.cancelOrder(orderId, currencyPair, null, null, null);
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }

    /**
     * Place a Futures Order.
     *
     * @param settle Settle currency (e.g., "usdt").
     * @param order  FuturesOrder object.
     * @return Created FuturesOrder
     */
    public FuturesOrder placeFuturesOrder(String settle, FuturesOrder order) {
        try {
            return futuresApi.createFuturesOrder(settle, order, null);
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }

    /**
     * Cancel a Futures Order.
     *
     * @param settle  Settle currency (e.g., "usdt").
     * @param orderId Order ID to cancel.
     * @return Cancelled FuturesOrder
     */
    public FuturesOrder cancelFuturesOrder(String settle, String orderId) {
        try {
            return futuresApi.cancelFuturesOrder(settle, orderId, null);
        } catch (ApiException e) {
            log.error("Gate API Error: {} {}", e.getCode(), e.getMessage());
            throw new RuntimeException("Gate API Error", e);
        }
    }
}
