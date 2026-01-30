package com.metaverse.coin.strategy.common.client.okx;

import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring.*;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

import java.util.List;
import java.util.Map;

public interface OkxApi {

    // --- Account ---

    @GET("/api/v5/account/balance")
    Call<OkxRestResponse<Balance>> getBalance(@Query("ccy") String ccy);

    @GET("/api/v5/account/positions")
    Call<OkxRestResponse<Map<String, Object>>> getPositions(@Query("instType") String instType, @Query("instId") String instId);

    @POST("/api/v5/account/set-leverage")
    Call<OkxRestResponse<Object>> setLeverage(@Body Map<String, Object> req);

    @GET("/api/v5/account/config")
    Call<OkxRestResponse<OkxAccountConfig>> getAccountConfig();

    @GET("/api/v5/account/max-size")
    Call<OkxRestResponse<OkxMaxSize>> getMaxSize(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/position-risk")
    Call<OkxRestResponse<OkxPositionRisk>> getPositionRisk(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/bills")
    Call<OkxRestResponse<OkxBill>> getBills(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/bills-archive")
    Call<OkxRestResponse<OkxBill>> getBillsArchive(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/max-avail-size")
    Call<OkxRestResponse<OkxMaxAvailSize>> getMaxAvailSize(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/trade-fee")
    Call<OkxRestResponse<OkxTradeFee>> getTradeFee(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/interest-accrued")
    Call<OkxRestResponse<OkxInterestAccrued>> getInterestAccrued(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/interest-rate")
    Call<OkxRestResponse<OkxInterestRate>> getInterestRate(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/account/max-loan")
    Call<OkxRestResponse<OkxMaxLoan>> getMaxLoan(@QueryMap Map<String, Object> queryMap);

    @POST("/api/v5/account/set-position-mode")
    Call<OkxRestResponse<OkxPositionMode>> setPositionMode(@Body Map<String, Object> req);

    @GET("/api/v5/account/leverage-info")
    Call<OkxRestResponse<OkxLeverageInfo>> getLeverageInfo(@QueryMap Map<String, Object> queryMap);

    // --- Trade ---

    @POST("/api/v5/trade/order")
    Call<OkxRestResponse<PlaceOrderRsp>> placeOrder(@Body PlaceOrderReq req);

    @POST("/api/v5/trade/batch-orders")
    Call<OkxRestResponse<PlaceOrderRsp>> batchOrders(@Body List<PlaceOrderReq> req);

    @POST("/api/v5/trade/cancel-order")
    Call<OkxRestResponse<CancelOrderRsp>> cancelOrder(@Body CancelOrderReq req);

    @POST("/api/v5/trade/cancel-batch-orders")
    Call<OkxRestResponse<CancelOrderRsp>> cancelBatchOrders(@Body List<CancelOrderReq> req);

    @POST("/api/v5/trade/amend-order")
    Call<OkxRestResponse<AmendOrderRsp>> amendOrder(@Body AmendOrderReq req);

    @POST("/api/v5/trade/amend-batch-orders")
    Call<OkxRestResponse<AmendOrderRsp>> amendBatchOrders(@Body List<AmendOrderReq> req);

    @POST("/api/v5/trade/mass-cancel")
    Call<OkxRestResponse<Object>> massCancel(@Body MassCancelOrderReq req);

    @POST("/api/v5/trade/cancel-all-after")
    Call<OkxRestResponse<CancelAllAfterRsp>> cancelAllAfter(@Body CancelAllAfterReq req);

    @POST("/api/v5/trade/close-position")
    Call<OkxRestResponse<OkxClosePositionRsp>> closePosition(@Body Map<String, Object> req);

    @GET("/api/v5/trade/order")
    Call<OkxRestResponse<OkxOrder>> getOrder(@Query("instId") String instId, @Query("ordId") String ordId, @Query("clOrdId") String clOrdId);

    @GET("/api/v5/trade/orders-history")
    Call<OkxRestResponse<OkxOrder>> getOrdersHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/trade/orders-history-archive")
    Call<OkxRestResponse<OkxOrder>> getOrdersHistoryArchive(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/trade/fills")
    Call<OkxRestResponse<OkxTransaction>> getFills(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/trade/fills-history")
    Call<OkxRestResponse<OkxTransaction>> getFillsHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/trade/orders-pending")
    Call<OkxRestResponse<OkxOrder>> getOrdersPending(@QueryMap Map<String, Object> queryMap);

    // --- Algo Trading ---

    @POST("/api/v5/trade/order-algo")
    Call<OkxRestResponse<PlaceAlgoOrderRsp>> placeAlgoOrder(@Body Map<String, Object> req);

    @POST("/api/v5/trade/cancel-algos")
    Call<OkxRestResponse<CancelAlgoOrderRsp>> cancelAlgoOrder(@Body List<Map<String, Object>> req);

    @POST("/api/v5/trade/amend-algos")
    Call<OkxRestResponse<AmendAlgoOrderRsp>> amendAlgoOrder(@Body List<AmendAlgoOrderReq> req);

    @GET("/api/v5/trade/order-algo")
    Call<OkxRestResponse<OkxAlgoOrder>> getAlgoOrder(@Query("algoId") String algoId, @Query("algoClOrdId") String algoClOrdId);

    @GET("/api/v5/trade/orders-algo-pending")
    Call<OkxRestResponse<OkxAlgoOrder>> getAlgoOrdersPending(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/trade/orders-algo-history")
    Call<OkxRestResponse<OkxAlgoOrder>> getAlgoOrdersHistory(@QueryMap Map<String, Object> queryMap);

    // --- Grid Trading ---

    @POST("/api/v5/tradingBot/grid/order-algo")
    Call<OkxRestResponse<PlaceGridAlgoOrderRsp>> placeGridAlgoOrder(@Body PlaceGridAlgoOrderReq req);

    @POST("/api/v5/tradingBot/grid/amend-order-algo")
    Call<OkxRestResponse<AmendGridAlgoOrderRsp>> amendGridAlgoOrder(@Body AmendGridAlgoOrderReq req);

    @POST("/api/v5/tradingBot/grid/adjust-investment")
    Call<OkxRestResponse<AddGridInvestmentRsp>> addGridInvestment(@Body AddGridInvestmentReq req);

    @POST("/api/v5/tradingBot/grid/stop-order-algo")
    Call<OkxRestResponse<StopGridAlgoOrderRsp>> stopGridAlgoOrder(@Body StopGridAlgoOrderReq req);

    @POST("/api/v5/tradingBot/grid/close-position")
    Call<OkxRestResponse<ClosePositionForContractGridRsp>> closePositionForContractGrid(@Body ClosePositionForContractGridReq req);

    @POST("/api/v5/tradingBot/grid/cancel-close-order")
    Call<OkxRestResponse<CancelClosePositionOrderForContractGridRsp>> cancelClosePositionOrderForContractGrid(@Body CancelClosePositionOrderForContractGridReq req);

    @POST("/api/v5/tradingBot/grid/instant-trigger")
    Call<OkxRestResponse<InstantTriggerGridAlgoOrderRsp>> instantTriggerGridAlgoOrder(@Body InstantTriggerGridAlgoOrderReq req);

    @GET("/api/v5/tradingBot/grid/orders-algo-history")
    Call<OkxRestResponse<GridAlgoOrder>> getGridAlgoOrdersHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/grid/orders-algo-details")
    Call<OkxRestResponse<GridAlgoOrder>> getGridAlgoOrderDetails(@Query("algoOrdId") String algoOrdId, @Query("algoClOrdId") String algoClOrdId);

    @GET("/api/v5/tradingBot/grid/sub-orders")
    Call<OkxRestResponse<GridAlgoSubOrder>> getGridAlgoSubOrders(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/grid/positions")
    Call<OkxRestResponse<GridAlgoOrderPosition>> getGridAlgoPositions(@QueryMap Map<String, Object> queryMap);

    @POST("/api/v5/tradingBot/grid/withdraw-income")
    Call<OkxRestResponse<SpotGridWithdrawIncomeRsp>> spotGridWithdrawIncome(@Body SpotGridWithdrawIncomeReq req);

    @GET("/api/v5/tradingBot/grid/min-investment")
    Call<OkxRestResponse<ComputeGridMinInvestmentRsp>> computeGridMinInvestment(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/grid/margin-balance")
    Call<OkxRestResponse<ComputeGridMarginBalanceRsp>> computeGridMarginBalance(@QueryMap Map<String, Object> queryMap);

    @POST("/api/v5/tradingBot/grid/adjust-margin-balance")
    Call<OkxRestResponse<AdjustGridMarginBalanceRsp>> adjustGridMarginBalance(@Body AdjustGridMarginBalanceReq req);

    @GET("/api/v5/tradingBot/grid/ai-param")
    Call<OkxRestResponse<GridApiParam>> getGridAiParam(@QueryMap Map<String, Object> queryMap);

    // --- Signal Bot ---

    @POST("/api/v5/tradingBot/signal/order-algo")
    Call<OkxRestResponse<PlaceSignalAlgoOrderRsp>> placeSignalAlgoOrder(@Body PlaceSignalAlgoOrderReq req);

    @POST("/api/v5/tradingBot/signal/sub-order")
    Call<OkxRestResponse<PlaceSignalAlgoSubOrderRsp>> placeSignalAlgoSubOrder(@Body PlaceSignalAlgoSubOrderReq req);

    @POST("/api/v5/tradingBot/signal/cancel-order-algo")
    Call<OkxRestResponse<CancelSignalAlgoOrderRsp>> cancelSignalAlgoOrder(@Body CancelSignalAlgoOrderReq req);

    @POST("/api/v5/tradingBot/signal/amend-order-algo")
    Call<OkxRestResponse<AmendSignalAlgoOrderRsp>> amendSignalAlgoOrder(@Body AmendSignalAlgoOrderReq req);

    @GET("/api/v5/tradingBot/signal/orders-algo-details")
    Call<OkxRestResponse<SignalAlgoOrder>> getSignalAlgoOrderDetails(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/signal/orders-algo-history")
    Call<OkxRestResponse<SignalAlgoOrder>> getSignalAlgoOrdersHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/signal/orders-algo-pending")
    Call<OkxRestResponse<SignalAlgoOrder>> getSignalAlgoOrdersPending(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/signal/sub-orders")
    Call<OkxRestResponse<SignalAlgoSubOrder>> getSignalAlgoSubOrders(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/signal/positions")
    Call<OkxRestResponse<SignalAlgoPosition>> getSignalAlgoPositions(@QueryMap Map<String, Object> queryMap);

    @POST("/api/v5/tradingBot/signal/close-position")
    Call<OkxRestResponse<CloseSignalPositionRsp>> closeSignalPosition(@Body CloseSignalPositionReq req);

    @POST("/api/v5/tradingBot/signal/margin-balance")
    Call<OkxRestResponse<ChangeSignalMarginBalanceRsp>> changeSignalMarginBalance(@Body ChangeSignalMarginBalanceReq req);

    // --- Recurring Buy ---

    @POST("/api/v5/tradingBot/recurring/order-algo")
    Call<OkxRestResponse<PlaceRecurringBuyAlgoOrderRsp>> placeRecurringBuyAlgoOrder(@Body PlaceRecurringBuyAlgoOrderReq req);

    @POST("/api/v5/tradingBot/recurring/cancel-order-algo")
    Call<OkxRestResponse<CancelRecurringBuyAlgoOrderRsp>> cancelRecurringBuyAlgoOrder(@Body CancelRecurringBuyAlgoOrderReq req);

    @POST("/api/v5/tradingBot/recurring/amend-order-algo")
    Call<OkxRestResponse<AmendRecurringBuyAlgoOrderRsp>> amendRecurringBuyAlgoOrder(@Body AmendRecurringBuyAlgoOrderReq req);

    @GET("/api/v5/tradingBot/recurring/orders-algo-pending")
    Call<OkxRestResponse<RecurringAlgoOrder>> getRecurringBuyAlgoOrdersPending(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/recurring/orders-algo-history")
    Call<OkxRestResponse<RecurringAlgoOrder>> getRecurringBuyAlgoOrdersHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/recurring/orders-algo-details")
    Call<OkxRestResponse<RecurringAlgoOrder>> getRecurringBuyAlgoOrderDetails(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/recurring/sub-orders")
    Call<OkxRestResponse<RecurringAlgoSubOrder>> getRecurringBuyAlgoSubOrders(@QueryMap Map<String, Object> queryMap);

    // --- Market Data ---

    @GET("/api/v5/market/tickers")
    Call<OkxRestResponse<Map<String, Object>>> getTickers(@Query("instType") String instType, @Query("uly") String uly);

    @GET("/api/v5/market/ticker")
    Call<OkxRestResponse<Map<String, Object>>> getTicker(@Query("instId") String instId);

    @GET("/api/v5/market/books")
    Call<OkxRestResponse<Map<String, Object>>> getOrderBook(@Query("instId") String instId, @Query("sz") Integer sz);

    @GET("/api/v5/market/candles")
    Call<OkxRestResponse<List<String>>> getCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/market/history-candles")
    Call<OkxRestResponse<List<String>>> getHistoryCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    // --- Public Data ---

    @GET("/api/v5/public/instruments")
    Call<OkxRestResponse<Map<String, Object>>> getInstruments(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId);

    @GET("/api/v5/public/delivery-exercise-history")
    Call<OkxRestResponse<Map<String, Object>>> getDeliveryExerciseHistory(@Query("instType") String instType, @Query("uly") String uly, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/public/open-interest")
    Call<OkxRestResponse<Map<String, Object>>> getOpenInterest(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId);

    @GET("/api/v5/public/funding-rate")
    Call<OkxRestResponse<Map<String, Object>>> getFundingRate(@Query("instId") String instId);

    @GET("/api/v5/public/funding-rate-history")
    Call<OkxRestResponse<Map<String, Object>>> getFundingRateHistory(@Query("instId") String instId, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/public/price-limit")
    Call<OkxRestResponse<Map<String, Object>>> getPriceLimit(@Query("instId") String instId);

    @GET("/api/v5/public/opt-summary")
    Call<OkxRestResponse<Map<String, Object>>> getOptSummary(@Query("uly") String uly, @Query("expTime") String expTime);

    @GET("/api/v5/public/estimated-price")
    Call<OkxRestResponse<Map<String, Object>>> getEstimatedPrice(@Query("instId") String instId);

    @GET("/api/v5/public/discount-rate-interest-free-quota")
    Call<OkxRestResponse<Map<String, Object>>> getDiscountRateInterestFreeQuota(@Query("ccy") String ccy);

    @GET("/api/v5/public/time")
    Call<OkxRestResponse<Map<String, Object>>> getSystemTime();

    @GET("/api/v5/public/liquidation-orders")
    Call<OkxRestResponse<Map<String, Object>>> getLiquidationOrders(@Query("instType") String instType, @Query("mgnMode") String mgnMode, @Query("instId") String instId, @Query("ccy") String ccy, @Query("uly") String uly, @Query("alias") String alias, @Query("state") String state, @Query("before") String before, @Query("after") String after, @Query("limit") Integer limit);

    @GET("/api/v5/public/mark-price")
    Call<OkxRestResponse<Map<String, Object>>> getMarkPrice(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId);

    @GET("/api/v5/public/position-tiers")
    Call<OkxRestResponse<Map<String, Object>>> getPositionTiers(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId, @Query("tdMode") String tdMode, @Query("tier") String tier);

    @GET("/api/v5/public/interest-rate-loan-quota")
    Call<OkxRestResponse<Map<String, Object>>> getInterestRateLoanQuota();

    @GET("/api/v5/public/underlying")
    Call<OkxRestResponse<Map<String, Object>>> getUnderlying(@Query("instType") String instType);

    @GET("/api/v5/public/insurance-fund")
    Call<OkxRestResponse<Map<String, Object>>> getInsuranceFund(@Query("instType") String instType, @Query("type") String type, @Query("uly") String uly, @Query("ccy") String ccy, @Query("before") String before, @Query("after") String after, @Query("limit") Integer limit);

    @GET("/api/v5/public/convert-contract-coin")
    Call<OkxRestResponse<Map<String, Object>>> convertContractCoin(@Query("type") String type, @Query("instId") String instId, @Query("sz") String sz, @Query("px") String px, @Query("unit") String unit);

    // --- Funding Account ---

    @GET("/api/v5/asset/currencies")
    Call<OkxRestResponse<Map<String, Object>>> getCurrencies(@Query("ccy") String ccy);

    @GET("/api/v5/asset/balances")
    Call<OkxRestResponse<Map<String, Object>>> getAssetBalances(@Query("ccy") String ccy);

    @POST("/api/v5/asset/transfer")
    Call<OkxRestResponse<Map<String, Object>>> assetTransfer(@Body Map<String, Object> req);

    @GET("/api/v5/asset/bills")
    Call<OkxRestResponse<Map<String, Object>>> getAssetBills(@Query("ccy") String ccy, @Query("type") String type, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @GET("/api/v5/asset/deposit-lightning")
    Call<OkxRestResponse<Map<String, Object>>> getDepositLightning(@Query("ccy") String ccy, @Query("amt") String amt, @Query("to") String to);

    @GET("/api/v5/asset/deposit-address")
    Call<OkxRestResponse<Map<String, Object>>> getDepositAddress(@Query("ccy") String ccy);

    @GET("/api/v5/asset/deposit-history")
    Call<OkxRestResponse<Map<String, Object>>> getDepositHistory(@Query("ccy") String ccy, @Query("depId") String depId, @Query("txId") String txId, @Query("type") String type, @Query("state") String state, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @POST("/api/v5/asset/withdrawal")
    Call<OkxRestResponse<Map<String, Object>>> withdrawal(@Body Map<String, Object> req);

    @GET("/api/v5/asset/withdrawal-history")
    Call<OkxRestResponse<Map<String, Object>>> getWithdrawalHistory(@Query("ccy") String ccy, @Query("wdId") String wdId, @Query("clientId") String clientId, @Query("txId") String txId, @Query("type") String type, @Query("state") String state, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    @POST("/api/v5/asset/cancel-withdrawal")
    Call<OkxRestResponse<Map<String, Object>>> cancelWithdrawal(@Body Map<String, Object> req);

    @POST("/api/v5/asset/convert-dust-assets")
    Call<OkxRestResponse<Map<String, Object>>> convertDustAssets(@Body Map<String, Object> req);

}
