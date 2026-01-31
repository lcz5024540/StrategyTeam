package com.metaverse.coin.strategy.common.client.okx;

import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.algo.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.signal.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.recurring.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.market.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.rubik.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.staking.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.savings.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.affiliate.*;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;

import java.util.List;
import java.util.Map;

public interface OkxApi {

    // --- Account (账户相关) ---

    /**
     * 获取余额 (Get Balance)
     * <br>
     * 获取账户余额信息。
     *
     * @param ccy 币种，如 BTC。如果为空，返回所有币种余额 (Currency, e.g. BTC)
     * @return 账户余额信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-balance">API Doc</a>
     */
    @GET("/api/v5/account/balance")
    Call<OkxRestResponse<Balance>> getBalance(@Query("ccy") String ccy);

    /**
     * 获取持仓信息 (Get Positions)
     * <br>
     * 获取该账户下的持仓信息。
     *
     * @param instType 产品类型 (Instrument Type): MARGIN, SWAP, FUTURES, OPTION
     * @param instId   产品ID (Instrument ID), 如 BTC-USDT-SWAP
     * @return 持仓信息列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-positions">API Doc</a>
     */
    @GET("/api/v5/account/positions")
    Call<OkxRestResponse<Map<String, Object>>> getPositions(@Query("instType") String instType, @Query("instId") String instId);

    /**
     * 设置杠杆倍数 (Set Leverage)
     *
     * @param req 请求参数，包含 instId, lever, mgnMode 等
     * @return 设置结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-set-leverage">API Doc</a>
     */
    @POST("/api/v5/account/set-leverage")
    Call<OkxRestResponse<Object>> setLeverage(@Body Map<String, Object> req);

    /**
     * 获取账户配置 (Get Account Configuration)
     * <br>
     * 查看账户当前的配置信息，如持仓模式、杠杆倍数等。
     *
     * @return 账户配置信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-account-configuration">API Doc</a>
     */
    @GET("/api/v5/account/config")
    Call<OkxRestResponse<OkxAccountConfig>> getAccountConfig();

    /**
     * 获取最大可买/卖数量 (Get Maximum Buy/Sell Amount)
     *
     * @param queryMap 查询参数，包含 instId, tdMode, ccy, px 等
     * @return 最大可买/卖数量
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-maximum-buy-sell-amount-or-open-amount">API Doc</a>
     */
    @GET("/api/v5/account/max-size")
    Call<OkxRestResponse<OkxMaxSize>> getMaxSize(@QueryMap Map<String, Object> queryMap);

    /**
     * 查看账户持仓风险 (Get Position Risk)
     * <br>
     * 查看账户下持仓的风险数据，如持仓量、维持保证金等。
     *
     * @param queryMap 查询参数
     * @return 账户持仓风险
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-position-risk">API Doc</a>
     */
    @GET("/api/v5/account/position-risk")
    Call<OkxRestResponse<OkxPositionRisk>> getPositionRisk(@QueryMap Map<String, Object> queryMap);

    /**
     * 账单流水查询 (Get Bills Details (Recent 7 Days))
     * <br>
     * 查询最近7天的账单流水。
     *
     * @param queryMap 查询参数
     * @return 账单流水
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-bills-details-recent-7-days">API Doc</a>
     */
    @GET("/api/v5/account/bills")
    Call<OkxRestResponse<OkxBill>> getBills(@QueryMap Map<String, Object> queryMap);

    /**
     * 查看账单流水（近3个月） (Get Bills Details (Recent 3 Months))
     * <br>
     * 查询最近3个月的账单流水。
     *
     * @param queryMap 查询参数
     * @return 账单流水
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-bills-details-recent-3-months">API Doc</a>
     */
    @GET("/api/v5/account/bills-archive")
    Call<OkxRestResponse<OkxBill>> getBillsArchive(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取最大可用数量 (Get Maximum Available Tradable Amount)
     * <br>
     * 获取账户的最大可用交易数量。
     *
     * @param queryMap 查询参数
     * @return 最大可用数量
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-maximum-available-tradable-amount">API Doc</a>
     */
    @GET("/api/v5/account/max-avail-size")
    Call<OkxRestResponse<OkxMaxAvailSize>> getMaxAvailSize(@QueryMap Map<String, Object> queryMap);

    /**
     * 查看费率 (Get Fee Rates)
     * <br>
     * 查看当前账户的交易费率。
     *
     * @param queryMap 查询参数
     * @return 费率信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-fee-rates">API Doc</a>
     */
    @GET("/api/v5/account/trade-fee")
    Call<OkxRestResponse<OkxTradeFee>> getTradeFee(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取计息记录 (Get Interest Accrued)
     * <br>
     * 获取借币的计息记录。
     *
     * @param queryMap 查询参数
     * @return 计息记录
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-interest-accrued">API Doc</a>
     */
    @GET("/api/v5/account/interest-accrued")
    Call<OkxRestResponse<OkxInterestAccrued>> getInterestAccrued(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取借币利率 (Get Interest Rate)
     * <br>
     * 获取借币的利率信息。
     *
     * @param queryMap 查询参数
     * @return 借币利率
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-interest-rate">API Doc</a>
     */
    @GET("/api/v5/account/interest-rate")
    Call<OkxRestResponse<OkxInterestRate>> getInterestRate(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取最大可借 (Get Maximum Loan Amount)
     * <br>
     * 获取最大可借币数量。
     *
     * @param queryMap 查询参数
     * @return 最大可借数量
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-maximum-loan-amount">API Doc</a>
     */
    @GET("/api/v5/account/max-loan")
    Call<OkxRestResponse<OkxMaxLoan>> getMaxLoan(@QueryMap Map<String, Object> queryMap);

    /**
     * 设置持仓模式 (Set Position Mode)
     * <br>
     * 设置账户的持仓模式（单向持仓/双向持仓）。
     *
     * @param req 请求参数
     * @return 设置结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-set-position-mode">API Doc</a>
     */
    @POST("/api/v5/account/set-position-mode")
    Call<OkxRestResponse<OkxPositionMode>> setPositionMode(@Body Map<String, Object> req);

    /**
     * 查看杠杆倍数 (Get Leverage)
     * <br>
     * 查看账户当前的杠杆倍数。
     *
     * @param queryMap 查询参数
     * @return 杠杆倍数信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-account-rest-api-get-leverage">API Doc</a>
     */
    @GET("/api/v5/account/leverage-info")
    Call<OkxRestResponse<OkxLeverageInfo>> getLeverageInfo(@QueryMap Map<String, Object> queryMap);

    // --- Trade (交易) ---

    /**
     * 下单 (Place Order)
     * <br>
     * 只有当您的账户有足够的资金才能下单。
     *
     * @param req 下单请求参数
     * @return 下单结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-place-order">API Doc</a>
     */
    @POST("/api/v5/trade/order")
    Call<OkxRestResponse<PlaceOrderRsp>> placeOrder(@Body PlaceOrderReq req);

    /**
     * 批量下单 (Place Multiple Orders)
     * <br>
     * 每次最多下 20 个订单。
     *
     * @param req 批量下单请求参数列表
     * @return 下单结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-place-multiple-orders">API Doc</a>
     */
    @POST("/api/v5/trade/batch-orders")
    Call<OkxRestResponse<PlaceOrderRsp>> batchOrders(@Body List<PlaceOrderReq> req);

    /**
     * 撤单 (Cancel Order)
     * <br>
     * 撤销未成交的订单。
     *
     * @param req 撤单请求参数
     * @return 撤单结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-cancel-order">API Doc</a>
     */
    @POST("/api/v5/trade/cancel-order")
    Call<OkxRestResponse<CancelOrderRsp>> cancelOrder(@Body CancelOrderReq req);

    /**
     * 批量撤单 (Cancel Multiple Orders)
     * <br>
     * 每次最多撤销 20 个订单。
     *
     * @param req 批量撤单请求参数列表
     * @return 撤单结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-cancel-multiple-orders">API Doc</a>
     */
    @POST("/api/v5/trade/cancel-batch-orders")
    Call<OkxRestResponse<CancelOrderRsp>> cancelBatchOrders(@Body List<CancelOrderReq> req);

    /**
     * 修改订单 (Amend Order)
     * <br>
     * 修改未成交订单的价格或数量。
     *
     * @param req 修改订单请求参数
     * @return 修改结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-amend-order">API Doc</a>
     */
    @POST("/api/v5/trade/amend-order")
    Call<OkxRestResponse<AmendOrderRsp>> amendOrder(@Body AmendOrderReq req);

    /**
     * 批量修改订单 (Amend Multiple Orders)
     * <br>
     * 每次最多修改 20 个订单。
     *
     * @param req 批量修改订单请求参数列表
     * @return 修改结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-amend-multiple-orders">API Doc</a>
     */
    @POST("/api/v5/trade/amend-batch-orders")
    Call<OkxRestResponse<AmendOrderRsp>> amendBatchOrders(@Body List<AmendOrderReq> req);

    /**
     * 批量撤单(按条件) (Mass Cancel Orders)
     * <br>
     * 根据产品ID或产品类型撤销所有未成交订单。
     *
     * @param req 批量撤单请求参数
     * @return 撤单结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-mass-cancel-orders">API Doc</a>
     */
    @POST("/api/v5/trade/mass-cancel")
    Call<OkxRestResponse<Object>> massCancel(@Body MassCancelOrderReq req);

    /**
     * 撤销所有订单(死手) (Cancel All After)
     * <br>
     * 设置一个倒计时，倒计时结束后自动撤销所有订单。
     *
     * @param req 倒计时请求参数
     * @return 设置结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-cancel-all-after">API Doc</a>
     */
    @POST("/api/v5/trade/cancel-all-after")
    Call<OkxRestResponse<CancelAllAfterRsp>> cancelAllAfter(@Body CancelAllAfterReq req);

    /**
     * 市价全平 (Close Position)
     * <br>
     * 市价平掉某个仓位的全部持仓。
     *
     * @param req 平仓请求参数
     * @return 平仓结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-post-close-position">API Doc</a>
     */
    @POST("/api/v5/trade/close-position")
    Call<OkxRestResponse<OkxClosePositionRsp>> closePosition(@Body Map<String, Object> req);

    /**
     * 获取订单信息 (Get Order Details)
     * <br>
     * 查寻单个订单的详细信息。
     *
     * @param instId  产品ID
     * @param ordId   订单ID
     * @param clOrdId 用户自定义ID
     * @return 订单信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-get-order-details">API Doc</a>
     */
    @GET("/api/v5/trade/order")
    Call<OkxRestResponse<OkxOrder>> getOrder(@Query("instId") String instId, @Query("ordId") String ordId, @Query("clOrdId") String clOrdId);

    /**
     * 获取历史订单 (Get Order History (Last 7 days))
     * <br>
     * 查询最近7天已完成、撤销的订单。
     *
     * @param queryMap 查询参数
     * @return 历史订单列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-get-order-history-last-7-days">API Doc</a>
     */
    @GET("/api/v5/trade/orders-history")
    Call<OkxRestResponse<OkxOrder>> getOrdersHistory(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取历史订单(近3个月) (Get Order History (Last 3 months))
     * <br>
     * 查询最近3个月已完成、撤销的订单。
     *
     * @param queryMap 查询参数
     * @return 历史订单列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-get-order-history-last-3-months">API Doc</a>
     */
    @GET("/api/v5/trade/orders-history-archive")
    Call<OkxRestResponse<OkxOrder>> getOrdersHistoryArchive(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取成交明细 (Get Transaction Details)
     * <br>
     * 查询最近3天的成交明细。
     *
     * @param queryMap 查询参数
     * @return 成交明细列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-get-transaction-details">API Doc</a>
     */
    @GET("/api/v5/trade/fills")
    Call<OkxRestResponse<OkxTransaction>> getFills(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取成交明细(近3个月) (Get Transaction Details (Last 3 months))
     * <br>
     * 查询最近3个月的成交明细。
     *
     * @param queryMap 查询参数
     * @return 成交明细列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-get-transaction-details-last-3-months">API Doc</a>
     */
    @GET("/api/v5/trade/fills-history")
    Call<OkxRestResponse<OkxTransaction>> getFillsHistory(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取未成交订单 (Get Pending Orders)
     * <br>
     * 获取当前账户下的未成交订单。
     *
     * @param queryMap 查询参数
     * @return 未成交订单列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-trade-get-order-list">API Doc</a>
     */
    @GET("/api/v5/trade/orders-pending")
    Call<OkxRestResponse<OkxOrder>> getOrdersPending(@QueryMap Map<String, Object> queryMap);

    // --- Algo Trading (策略交易) ---

    /**
     * 委托策略下单 (Place Algo Order)
     * <br>
     * 委托止盈止损、计划委托、冰山委托、时间加权委托等策略订单。
     *
     * @param req 策略下单请求参数
     * @return 下单结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-algo-trading-post-place-algo-order">API Doc</a>
     */
    @POST("/api/v5/trade/order-algo")
    Call<OkxRestResponse<PlaceAlgoOrderRsp>> placeAlgoOrder(@Body Map<String, Object> req);

    /**
     * 撤销策略委托订单 (Cancel Algo Order)
     * <br>
     * 撤销未触发的策略委托订单。
     *
     * @param req 撤单请求参数列表
     * @return 撤单结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-algo-trading-post-cancel-algo-order">API Doc</a>
     */
    @POST("/api/v5/trade/cancel-algos")
    Call<OkxRestResponse<CancelAlgoOrderRsp>> cancelAlgoOrder(@Body List<Map<String, Object>> req);

    /**
     * 修改策略委托订单 (Amend Algo Order)
     * <br>
     * 修改未触发的策略委托订单。
     *
     * @param req 修改请求参数列表
     * @return 修改结果
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-algo-trading-post-amend-algo-order">API Doc</a>
     */
    @POST("/api/v5/trade/amend-algos")
    Call<OkxRestResponse<AmendAlgoOrderRsp>> amendAlgoOrder(@Body List<AmendAlgoOrderReq> req);

    /**
     * 获取策略委托单信息 (Get Algo Order Details)
     * <br>
     * 获取单个策略委托单的详细信息。
     *
     * @param algoId      策略委托单ID
     * @param algoClOrdId 用户自定义ID
     * @return 策略委托单信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-algo-trading-get-algo-order-details">API Doc</a>
     */
    @GET("/api/v5/trade/order-algo")
    Call<OkxRestResponse<OkxAlgoOrder>> getAlgoOrder(@Query("algoId") String algoId, @Query("algoClOrdId") String algoClOrdId);

    /**
     * 获取未完成策略委托单 (Get Pending Algo Orders)
     * <br>
     * 获取当前账户下未触发的策略委托单。
     *
     * @param queryMap 查询参数
     * @return 未完成策略委托单列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-algo-trading-get-algo-order-list">API Doc</a>
     */
    @GET("/api/v5/trade/orders-algo-pending")
    Call<OkxRestResponse<OkxAlgoOrder>> getAlgoOrdersPending(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取历史策略委托单 (Get History Algo Orders)
     * <br>
     * 获取最近3个月的历史策略委托单。
     *
     * @param queryMap 查询参数
     * @return 历史策略委托单列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-algo-trading-get-algo-order-history">API Doc</a>
     */
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

    @POST("/api/v5/tradingBot/grid/order-instant-trigger")
    Call<OkxRestResponse<InstantTriggerGridAlgoOrderRsp>> instantTriggerGridAlgoOrder(@Body InstantTriggerGridAlgoOrderReq req);

    @GET("/api/v5/tradingBot/grid/orders-algo-history")
    Call<OkxRestResponse<GridAlgoOrder>> getGridAlgoOrdersHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/grid/orders-algo-pending")
    Call<OkxRestResponse<GridAlgoOrder>> getGridAlgoOrdersPending(@QueryMap Map<String, Object> queryMap);

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

    @POST("/api/v5/tradingBot/grid/compute-margin-balance")
    Call<OkxRestResponse<ComputeGridMarginBalanceRsp>> computeGridMarginBalancePost(@Body ComputeGridMarginBalanceReq req);

    @POST("/api/v5/tradingBot/grid/amend-algo-basic-param")
    Call<OkxRestResponse<AmendGridAlgoBasicParamRsp>> amendGridAlgoBasicParam(@Body AmendGridAlgoBasicParamReq req);

    @POST("/api/v5/tradingBot/grid/min-investment")
    Call<OkxRestResponse<ComputeGridMinInvestmentRsp>> computeGridMinInvestmentPost(@Body ComputeGridMinInvestmentReq req);

    // --- Finance (Savings) ---

    @GET("/api/v5/finance/savings/balance")
    Call<OkxRestResponse<OkxSavingsBalance>> getSavingsBalance(@Query("ccy") String ccy);

    @POST("/api/v5/finance/savings/purchase-redempt")
    Call<OkxRestResponse<OkxSavingsPurchaseRedemptRsp>> savingsPurchaseRedempt(@Body OkxSavingsPurchaseRedemptReq req);

    @POST("/api/v5/finance/savings/set-lending-rate")
    Call<OkxRestResponse<OkxSavingsSetLendingRateRsp>> setSavingsLendingRate(@Body OkxSavingsSetLendingRateReq req);

    @GET("/api/v5/finance/savings/lending-history")
    Call<OkxRestResponse<OkxSavingsLendingHistory>> getSavingsLendingHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/finance/savings/lending-rate-summary")
    Call<OkxRestResponse<OkxSavingsLendingRateSummary>> getSavingsLendingRateSummary(@Query("ccy") String ccy);

    @GET("/api/v5/finance/savings/lending-rate-history")
    Call<OkxRestResponse<OkxSavingsLendingRateHistory>> getSavingsLendingRateHistory(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/public/rsi-back-testing")
    Call<OkxRestResponse<RsiBackTestingRsp>> rsiBackTesting(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/tradingBot/grid/grid-quantity")
    Call<OkxRestResponse<GridQuantityRsp>> getGridQuantity(@QueryMap Map<String, Object> queryMap);

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

    @POST("/api/v5/tradingBot/recurring/stop-order-algo")
    Call<OkxRestResponse<CancelRecurringBuyAlgoOrderRsp>> stopRecurringBuyAlgoOrder(@Body CancelRecurringBuyAlgoOrderReq req);

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

    // --- Market Data (行情数据) ---

    /**
     * 获取所有产品行情信息 (Get Tickers)
     * <br>
     * 获取产品行情信息。
     *
     * @param instType 产品类型 (SPOT, SWAP, FUTURES, OPTION)
     * @param uly      标的指数
     * @return 行情信息列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-tickers">API Doc</a>
     */
    @GET("/api/v5/market/tickers")
    Call<OkxRestResponse<OkxTicker>> getTickers(@Query("instType") String instType, @Query("uly") String uly);

    /**
     * 获取单个产品行情信息 (Get Ticker)
     * <br>
     * 获取单个产品的行情信息。
     *
     * @param instId 产品ID
     * @return 行情信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-ticker">API Doc</a>
     */
    @GET("/api/v5/market/ticker")
    Call<OkxRestResponse<OkxTicker>> getTicker(@Query("instId") String instId);

    /**
     * 获取指数行情 (Get Index Tickers)
     * <br>
     * 获取指数行情信息。
     *
     * @param quoteCcy 计价货币
     * @param instId   指数ID
     * @return 指数行情信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-index-tickers">API Doc</a>
     */
    @GET("/api/v5/market/index-tickers")
    Call<OkxRestResponse<OkxIndexTicker>> getIndexTickers(@Query("quoteCcy") String quoteCcy, @Query("instId") String instId);

    /**
     * 获取产品深度 (Get Order Book)
     * <br>
     * 获取产品的深度数据。
     *
     * @param instId 产品ID
     * @param sz     深度档位数量 (1, 400, 5000)
     * @return 深度数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-order-book">API Doc</a>
     */
    @GET("/api/v5/market/books")
    Call<OkxRestResponse<OkxOrderBook>> getOrderBook(@Query("instId") String instId, @Query("sz") Integer sz);

    /**
     * 获取产品深度(全量) (Get Order Book Full)
     * <br>
     * 获取产品的全量深度数据。
     *
     * @param instId 产品ID
     * @param sz     深度档位数量
     * @return 全量深度数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-order-book">API Doc</a>
     */
    @GET("/api/v5/market/books-full")
    Call<OkxRestResponse<OkxOrderBook>> getOrderBookFull(@Query("instId") String instId, @Query("sz") Integer sz);

    /**
     * 获取K线数据 (Get Candlesticks)
     * <br>
     * 获取产品的K线数据。
     *
     * @param instId 产品ID
     * @param bar    时间粒度 (1m, 5m, 1H, 1D, etc.)
     * @param after  请求此时间戳之前的数据
     * @param before 请求此时间戳之后的数据
     * @param limit  返回结果数量
     * @return K线数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-candlesticks">API Doc</a>
     */
    @GET("/api/v5/market/candles")
    Call<OkxRestResponse<OkxCandle>> getCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    /**
     * 获取历史K线数据 (Get History Candlesticks)
     * <br>
     * 获取最近几年的历史K线数据。
     *
     * @param instId 产品ID
     * @param bar    时间粒度
     * @param after  请求此时间戳之前的数据
     * @param before 请求此时间戳之后的数据
     * @param limit  返回结果数量
     * @return 历史K线数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-candlesticks-history">API Doc</a>
     */
    @GET("/api/v5/market/history-candles")
    Call<OkxRestResponse<OkxCandle>> getHistoryCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    /**
     * 获取指数K线数据 (Get Index Candlesticks)
     * <br>
     * 获取指数的K线数据。
     *
     * @param instId 指数ID
     * @param bar    时间粒度
     * @param after  请求此时间戳之前的数据
     * @param before 请求此时间戳之后的数据
     * @param limit  返回结果数量
     * @return 指数K线数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-index-candlesticks">API Doc</a>
     */
    @GET("/api/v5/market/index-candles")
    Call<OkxRestResponse<OkxCandle>> getIndexCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    /**
     * 获取标记价格K线数据 (Get Mark Price Candlesticks)
     * <br>
     * 获取标记价格的K线数据。
     *
     * @param instId 产品ID
     * @param bar    时间粒度
     * @param after  请求此时间戳之前的数据
     * @param before 请求此时间戳之后的数据
     * @param limit  返回结果数量
     * @return 标记价格K线数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-mark-price-candlesticks">API Doc</a>
     */
    @GET("/api/v5/market/mark-price-candles")
    Call<OkxRestResponse<OkxCandle>> getMarkPriceCandles(@Query("instId") String instId, @Query("bar") String bar, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    /**
     * 获取交易大数据 (Get Trades)
     * <br>
     * 获取最近的成交数据。
     *
     * @param instId 产品ID
     * @param limit  返回结果数量
     * @return 交易数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-trades">API Doc</a>
     */
    @GET("/api/v5/market/trades")
    Call<OkxRestResponse<OkxTrade>> getTrades(@Query("instId") String instId, @Query("limit") Integer limit);

    /**
     * 获取期权品种成交数据 (Get Option Trades by Instrument Family)
     * <br>
     * 获取期权品种的成交数据。
     *
     * @param instFamily 交易品种，如 BTC-USD
     * @return 期权成交数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-option-trades">API Doc</a>
     */
    @GET("/api/v5/market/option/instrument-family-trades")
    Call<OkxRestResponse<OkxTrade>> getOptionInstrumentFamilyTrades(@Query("instFamily") String instFamily);

    /**
     * 获取历史成交数据 (Get History Trades)
     * <br>
     * 获取最近3个月的历史成交数据。
     *
     * @param instId 产品ID
     * @param type   分页类型
     * @param after  请求此时间戳之前的数据
     * @param before 请求此时间戳之后的数据
     * @param limit  返回结果数量
     * @return 历史成交数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-history-trades">API Doc</a>
     */
    @GET("/api/v5/market/history-trades")
    Call<OkxRestResponse<OkxTrade>> getHistoryTrades(@Query("instId") String instId, @Query("type") String type, @Query("after") String after, @Query("before") String before, @Query("limit") Integer limit);

    /**
     * 获取平台24小时总成交量 (Get Platform 24 Volume)
     * <br>
     * 获取平台24小时的总成交量。
     *
     * @return 平台24小时总成交量
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-platform-24-volume">API Doc</a>
     */
    @GET("/api/v5/market/platform-24-volume")
    Call<OkxRestResponse<OkxPlatformVolume>> getPlatform24Volume();

    /**
     * 获取集合竞价详情 (Get Call Auction Details)
     * <br>
     * 获取集合竞价期间的详情信息。
     *
     * @param instId 产品ID
     * @return 集合竞价详情
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-call-auction-details">API Doc</a>
     */
    @GET("/api/v5/market/call-auction-details")
    Call<OkxRestResponse<Map<String, Object>>> getCallAuctionDetails(@Query("instId") String instId);

    /**
     * 获取Oracle价格 (Get Oracle)
     * <br>
     * 获取Oracle价格信息。
     *
     * @return Oracle价格
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-oracle">API Doc</a>
     */
    @GET("/api/v5/market/oracle")
    Call<OkxRestResponse<OkxOracle>> getOracle();

    /**
     * 获取汇率 (Get Exchange Rate)
     * <br>
     * 获取法币汇率。
     *
     * @return 汇率信息
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-exchange-rate">API Doc</a>
     */
    @GET("/api/v5/market/exchange-rate")
    Call<OkxRestResponse<OkxExchangeRate>> getExchangeRate();

    /**
     * 获取指数成分 (Get Index Components)
     * <br>
     * 获取指数成分信息。
     *
     * @param index 指数，如 BTC-USD
     * @return 指数成分
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-index-components">API Doc</a>
     */
    @GET("/api/v5/market/index-components")
    Call<OkxRestResponse<OkxIndexComponent>> getIndexComponents(@Query("index") String index);

    /**
     * 获取大宗交易行情 (Get Block Tickers)
     * <br>
     * 获取大宗交易的行情信息。
     *
     * @param instType 产品类型
     * @param uly      标的指数
     * @return 大宗交易行情
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-block-tickers">API Doc</a>
     */
    @GET("/api/v5/market/block-tickers")
    Call<OkxRestResponse<OkxTicker>> getBlockTickers(@Query("instType") String instType, @Query("uly") String uly);

    /**
     * 获取大宗交易单个产品行情 (Get Block Ticker)
     * <br>
     * 获取大宗交易单个产品的行情信息。
     *
     * @param instId 产品ID
     * @return 大宗交易行情
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-block-ticker">API Doc</a>
     */
    @GET("/api/v5/market/block-ticker")
    Call<OkxRestResponse<OkxTicker>> getBlockTicker(@Query("instId") String instId);

    /**
     * 获取大宗交易成交数据 (Get Block Trades)
     * <br>
     * 获取大宗交易的成交数据。
     *
     * @param instId 产品ID
     * @return 大宗交易成交数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#order-book-trading-market-data-get-block-trades">API Doc</a>
     */
    @GET("/api/v5/market/block-trades")
    Call<OkxRestResponse<OkxTrade>> getBlockTrades(@Query("instId") String instId);

    // --- Public Data ---

    @GET("/api/v5/public/instruments")
    Call<OkxRestResponse<Map<String, Object>>> getInstruments(@Query("instType") String instType, @Query("uly") String uly, @Query("instId") String instId);

    @GET("/api/v5/public/option-trades")
    Call<OkxRestResponse<OkxTrade>> getPublicOptionTrades(@Query("instId") String instId, @Query("limit") Integer limit);

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

    // --- Rubik (Trading Statistics) ---

    /**
     * 获取交易大数据支持币种 (Get Support Coin)
     * <br>
     * 获取交易大数据支持的币种。
     *
     * @return 支持币种列表
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-support-coin">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/trading-data/support-coin")
    Call<OkxRestResponse<String>> getSupportCoin();

    /**
     * 获取主动买入/卖出情况 (Get Taker Volume)
     * <br>
     * 获取现货和合约的主动买入/卖出情况。
     *
     * @param ccy      币种，如 BTC
     * @param instType 产品类型 (SPOT, SWAP, FUTURES, OPTION)
     * @param begin    开始时间戳
     * @param end      结束时间戳
     * @param period   时间粒度 (5m, 1H, 1D)
     * @return 主动买入/卖出情况
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-taker-volume">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/taker-volume")
    Call<OkxRestResponse<OkxTakerVolume>> getTakerVolume(@Query("ccy") String ccy, @Query("instType") String instType, @Query("begin") String begin, @Query("end") String end, @Query("period") String period);

    /**
     * 获取杠杆借币多空比 (Get Margin Lending Ratio)
     * <br>
     * 获取杠杆借币的多空比。
     *
     * @param ccy    币种
     * @param begin  开始时间戳
     * @param end    结束时间戳
     * @param period 时间粒度
     * @return 杠杆借币多空比
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-margin-lending-ratio">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/margin/loan-ratio")
    Call<OkxRestResponse<OkxMarginLendingRatio>> getMarginLendingRatio(@Query("ccy") String ccy, @Query("begin") String begin, @Query("end") String end, @Query("period") String period);

    /**
     * 获取精英交易员多空持仓人数比 (Get Long/Short Ratio)
     * <br>
     * 获取精英交易员的多空持仓人数比。
     *
     * @param instId 产品ID
     * @param begin  开始时间戳
     * @param end    结束时间戳
     * @param period 时间粒度
     * @return 多空持仓人数比
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-long-short-ratio">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/contracts/long-short-account-ratio-contract-top-trader")
    Call<OkxRestResponse<OkxLongShortRatio>> getLongShortAccountRatio(@Query("instId") String instId, @Query("begin") String begin, @Query("end") String end, @Query("period") String period);

    /**
     * 获取合约持仓量及交易量 (Get Contract Open Interest and Volume)
     * <br>
     * 获取合约的持仓量及交易量。
     *
     * @param ccy      币种
     * @param instType 产品类型
     * @param begin    开始时间戳
     * @param end      结束时间戳
     * @param period   时间粒度
     * @return 持仓量及交易量
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-contract-open-interest-and-volume">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/contracts/open-interest-volume")
    Call<OkxRestResponse<OkxOpenInterestVolume>> getOpenInterestVolume(@Query("ccy") String ccy, @Query("instType") String instType, @Query("begin") String begin, @Query("end") String end, @Query("period") String period);

    // --- Finance (On-chain Earn / Staking DeFi) ---

    @GET("/api/v5/finance/staking-defi/offers")
    Call<OkxRestResponse<OkxStakingOffer>> getStakingOffers(@QueryMap Map<String, Object> queryMap);

    @POST("/api/v5/finance/staking-defi/purchase")
    Call<OkxRestResponse<OkxStakingPurchaseRsp>> purchaseStaking(@Body OkxStakingPurchaseReq req);

    @POST("/api/v5/finance/staking-defi/redeem")
    Call<OkxRestResponse<OkxStakingRedeemRsp>> redeemStaking(@Body OkxStakingRedeemReq req);

    @POST("/api/v5/finance/staking-defi/cancel")
    Call<OkxRestResponse<OkxStakingCancelRsp>> cancelStaking(@Body OkxStakingCancelReq req);

    @GET("/api/v5/finance/staking-defi/orders-active")
    Call<OkxRestResponse<OkxStakingOrder>> getActiveStakingOrders(@QueryMap Map<String, Object> queryMap);

    @GET("/api/v5/finance/staking-defi/orders-history")
    Call<OkxRestResponse<OkxStakingOrder>> getStakingOrdersHistory(@QueryMap Map<String, Object> queryMap);

    /**
     * 获取合约持仓量 (Get Open Interest History)
     * <br>
     * 获取合约的持仓量历史数据。
     *
     * @param instId 产品ID
     * @param period 时间粒度
     * @param begin  开始时间戳
     * @param end    结束时间戳
     * @param limit  返回结果数量
     * @return 合约持仓量历史
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-open-interest-history">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/contracts/open-interest-history")
    Call<OkxRestResponse<OkxOpenInterestHistory>> getOpenInterestHistory(@Query("instId") String instId, @Query("period") String period, @Query("begin") String begin, @Query("end") String end, @Query("limit") String limit);

    /**
     * 获取合约主动买入/卖出情况 (Get Taker Volume Contract)
     * <br>
     * 获取合约的主动买入/卖出情况。
     *
     * @param instId 产品ID
     * @param period 时间粒度
     * @param begin  开始时间戳
     * @param end    结束时间戳
     * @param limit  返回结果数量
     * @return 合约主动买入/卖出情况
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-taker-volume-contract">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/taker-volume-contract")
    Call<OkxRestResponse<OkxTakerVolume>> getTakerVolumeContract(@Query("instId") String instId, @Query("period") String period, @Query("begin") String begin, @Query("end") String end, @Query("limit") String limit);

    /**
     * 获取精英交易员多空持仓人数比 (Get Long/Short Position Ratio)
     * <br>
     * 获取精英交易员的多空持仓人数比。
     *
     * @param instId 产品ID
     * @param period 时间粒度
     * @param begin  开始时间戳
     * @param end    结束时间戳
     * @param limit  返回结果数量
     * @return 精英交易员多空持仓人数比
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-long-short-position-ratio-contract-top-trader">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/contracts/long-short-position-ratio-contract-top-trader")
    Call<OkxRestResponse<OkxLongShortRatio>> getLongShortPositionRatioContractTopTrader(@Query("instId") String instId, @Query("period") String period, @Query("begin") String begin, @Query("end") String end, @Query("limit") String limit);

    /**
     * 获取多空持仓人数比 (Get Long/Short Account Ratio)
     * <br>
     * 获取所有用户的多空持仓人数比。
     *
     * @param instId 产品ID
     * @param period 时间粒度
     * @param begin  开始时间戳
     * @param end    结束时间戳
     * @param limit  返回结果数量
     * @return 多空持仓人数比
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-long-short-account-ratio-contract">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/contracts/long-short-account-ratio-contract")
    Call<OkxRestResponse<OkxLongShortRatio>> getLongShortAccountRatioContract(@Query("instId") String instId, @Query("period") String period, @Query("begin") String begin, @Query("end") String end, @Query("limit") String limit);

    /**
     * 获取期权持仓量及交易量 (Get Option Open Interest and Volume)
     * <br>
     * 获取期权的持仓量及交易量。
     *
     * @param ccy    币种
     * @param period 时间粒度
     * @return 期权持仓量及交易量
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-option-open-interest-volume">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/option/open-interest-volume")
    Call<OkxRestResponse<OkxOpenInterestVolume>> getOptionOpenInterestVolume(@Query("ccy") String ccy, @Query("period") String period);

    /**
     * 获取看涨/看跌期权合约 持仓总量比/交易总量比 (Get Put/Call Ratio)
     * <br>
     * 获取看涨/看跌期权合约的持仓总量比和交易总量比。
     *
     * @param ccy    币种
     * @param period 时间粒度
     * @return 看涨/看跌期权合约比率
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-option-open-interest-volume-ratio">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/option/open-interest-volume-ratio")
    Call<OkxRestResponse<OkxPutCallRatio>> getOptionOpenInterestVolumeRatio(@Query("ccy") String ccy, @Query("period") String period);

    /**
     * 获取期权合约到期日 持仓总量及交易总量 (Get Option Open Interest and Volume by Expiry)
     * <br>
     * 获取期权合约按到期日的持仓总量及交易总量。
     *
     * @param ccy    币种
     * @param period 时间粒度
     * @return 期权合约到期日数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-option-open-interest-volume-expiry">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/option/open-interest-volume-expiry")
    Call<OkxRestResponse<OkxOpenInterestVolumeExpiry>> getOptionOpenInterestVolumeExpiry(@Query("ccy") String ccy, @Query("period") String period);

    /**
     * 获取期权合约执行价格 持仓总量及交易总量 (Get Option Open Interest and Volume by Strike)
     * <br>
     * 获取期权合约按执行价格的持仓总量及交易总量。
     *
     * @param ccy     币种
     * @param expTime 到期日
     * @param period  时间粒度
     * @return 期权合约执行价格数据
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-option-open-interest-volume-strike">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/option/open-interest-volume-strike")
    Call<OkxRestResponse<OkxOpenInterestVolumeStrike>> getOptionOpenInterestVolumeStrike(@Query("ccy") String ccy, @Query("expTime") String expTime, @Query("period") String period);

    /**
     * 获取期权大单成交 (Get Option Taker Block Volume)
     * <br>
     * 获取期权的大单成交数据。
     *
     * @param ccy    币种
     * @param period 时间粒度
     * @param begin  开始时间戳
     * @param end    结束时间戳
     * @return 期权大单成交
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-option-taker-block-volume">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/option/taker-block-volume")
    Call<OkxRestResponse<OkxTakerVolume>> getOptionTakerBlockVolume(@Query("ccy") String ccy, @Query("period") String period, @Query("begin") String begin, @Query("end") String end);

    /**
     * 获取主动买入/卖出情况 (Get Taker Flow)
     * <br>
     * 获取主动买入/卖出情况。
     *
     * @param ccy    币种
     * @param period 时间粒度
     * @return 主动买入/卖出情况
     * @see <a href="https://www.okx.com/docs-v5/zh/#trading-statistics-rest-api-get-taker-flow">API Doc</a>
     */
    @GET("/api/v5/rubik/stat/taker-flow")
    Call<OkxRestResponse<OkxTakerFlow>> getTakerFlow(@Query("ccy") String ccy, @Query("period") String period);

    // --- Affiliate ---

    @GET("/api/v5/affiliate/invitee/detail")
    Call<OkxRestResponse<OkxAffiliateInviteeDetail>> getAffiliateInviteeDetail(@QueryMap Map<String, Object> queryMap);

}
