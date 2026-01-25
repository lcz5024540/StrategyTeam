package com.metaverse.coin.strategy.common.client.gate;

import retrofit2.Call;
import retrofit2.http.*;

import java.util.List;
import java.util.Map;

public interface GateApi {

    // --- Spot ---
    @GET("spot/tickers")
    Call<String> getSpotTickers(@Query("currency_pair") String currencyPair);

    @GET("spot/accounts")
    Call<String> getSpotAccounts(@Query("currency") String currency);

    @POST("spot/orders")
    Call<String> placeSpotOrder(@Body Map<String, Object> body);

    @DELETE("spot/orders/{order_id}")
    Call<String> cancelSpotOrder(@Path("order_id") String orderId, @Query("currency_pair") String currencyPair);

    // --- Futures ---
    @GET("futures/{settle}/tickers")
    Call<String> getFuturesTickers(@Path("settle") String settle, @Query("contract") String contract);

    @GET("futures/{settle}/accounts")
    Call<String> getFuturesAccounts(@Path("settle") String settle);

    @POST("futures/{settle}/orders")
    Call<String> placeFuturesOrder(@Path("settle") String settle, @Body Map<String, Object> body);

    @DELETE("futures/{settle}/orders/{order_id}")
    Call<String> cancelFuturesOrder(@Path("settle") String settle, @Path("order_id") String orderId);
    
    // --- Wallet ---
    @GET("wallet/total_balance")
    Call<String> getTotalBalance(@Query("currency") String currency);
}
