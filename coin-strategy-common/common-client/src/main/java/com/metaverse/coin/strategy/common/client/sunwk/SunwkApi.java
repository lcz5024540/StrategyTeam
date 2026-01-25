package com.metaverse.coin.strategy.common.client.sunwk;

import retrofit2.Call;
import retrofit2.http.*;

import java.util.Map;

public interface SunwkApi {

    // --- Account (账户类) ---
    // Assuming endpoint for balance/assets
    @GET("/sapi/v1/account/assets")
    Call<String> getAssets(@QueryMap Map<String, Object> queryMap);

    // --- Order (订单类) ---
    @GET("/sapi/v1/trade/order")
    Call<String> getOrder(@Query("contract_code") String contractCode, @Query("order_id") String orderId, @Query("client_order_id") String clientOrderId);

    @POST("/sapi/v1/trade/order")
    Call<String> placeOrder(@Body Map<String, Object> req);

    @POST("/sapi/v1/trade/cancel_order")
    Call<String> cancelOrder(@Body Map<String, Object> req);

    // --- Position (持仓类) ---
    @GET("/sapi/v1/trade/position/opens")
    Call<String> getPositions(@Query("contract_code") String contractCode, @Query("symbol") String symbol);

    // --- Market Data (行情数据) ---
    @GET("/sapi/v1/market/ticker")
    Call<String> getTicker(@Query("contract_code") String contractCode);

    @GET("/sapi/v1/market/kline")
    Call<String> getKlines(@Query("contract_code") String contractCode, @Query("period") String period, @Query("limit") Integer limit);

    // --- Wallet (钱包) ---
    @GET("/sapi/v1/wallet/deposit/address")
    Call<String> getDepositAddress(@Query("currency") String currency);

}
