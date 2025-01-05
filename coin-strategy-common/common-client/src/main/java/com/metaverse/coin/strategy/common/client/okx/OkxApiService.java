package com.metaverse.coin.strategy.common.client.okx;

import com.metaverse.coin.strategy.common.client.ApiService;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.account.Balance;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.RsiBackTesting;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.*;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.CancelOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.PlaceOrderReq;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.order.booking.PlaceOrderRsp;
import com.metaverse.coin.strategy.common.model.okx.enums.AlgoOrderType;
import com.metaverse.coin.strategy.common.model.okx.enums.ContractDirection;
import com.metaverse.coin.strategy.common.model.okx.enums.InstrumentType;
import com.metaverse.coin.strategy.common.model.okx.enums.TriggerCondition;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;


@Slf4j
public class OkxApiService extends ApiService {

//    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final boolean simulated;
    private final OkxApi api;
    private final OkHttpClient client;

//    public OkxApiService(String apiKey, String secretKey, String passphrase) {
//        this(apiKey, secretKey, passphrase, false);
//    }
//
//    public OkxApiService(String apiKey, String secretKey, String passphrase, boolean simulated) {
//        this(buildApi(apiKey, secretKey, passphrase, simulated, DEFAULT_TIMEOUT),
//                defaultClient(apiKey, secretKey, passphrase, simulated, DEFAULT_TIMEOUT),
//                simulated);
//    }

    public OkxApiService(final OkxApi api, final OkHttpClient client, boolean simulated) {
        this.api = api;
        this.client = client;
        this.simulated = simulated;
    }

//    public static OkxApi buildApi(String apiKey, String secretKey, String passphrase, boolean simulated, Duration timeout) {
//        ObjectMapper mapper = defaultObjectMapper();
//        OkHttpClient client = defaultClient(apiKey, secretKey, passphrase, simulated, timeout);
//        Retrofit retrofit = defaultRetrofit(client, mapper, simulated);
//        return retrofit.create(OkxApi.class);
//    }



    // 开始接口

    /**
     * 查看账户余额
     *
     * @param ccy 支持多币种查询（不超过20个），币种之间半角逗号分隔
     * @return AccountBalance list
     */
    public OkxRestResponse<Balance> getBalance(String ccy) {
        return execute(api.getBalance(ccy));
    }

    /**
     * 下单
     *
     * @param request request
     * @return OkxRestResponse<TradeOrderRsp>
     */
    public OkxRestResponse<PlaceOrderRsp> placeOrder(PlaceOrderReq request) {
        return execute(api.placeOrder(request));
    }

    /**
     * 撤单
     *
     * @param request request
     * @return OkxRestResponse<CancelOrderRsp>
     */
    public OkxRestResponse<CancelOrderRsp> cancelOrder(CancelOrderReq request) {
        return execute(api.cancelOrder(request));
    }


    // Grid trading start

    /**
     * 网格策略委托下单
     *
     * @param request request
     * @return PlaceGridAlgoOrderRsp
     */
    public OkxRestResponse<PlaceGridAlgoOrderRsp> placeGridAlgoOrder(PlaceGridAlgoOrderReq request) {
        return execute(api.placeGridAlgoOrder(request));
    }

    /**
     * 修改网格策略订单
     *
     * @param request request
     * @return AmendGridAlgoOrderRsp
     */
    public OkxRestResponse<AmendGridAlgoOrderRsp> amendGridAlgoOrder(AmendGridAlgoOrderReq request) {
        return execute(api.amendGridAlgoOrder(request));
    }

    /**
     * 网格策略停止
     * 每次最多可以撤销10个网格策略。
     *
     * @param request request
     * @return StopGridAlgoOrderRsp
     */
    public OkxRestResponse<StopGridAlgoOrderRsp> stopGridAlgoOrder(StopGridAlgoOrderReq request) {
        return execute(api.stopGridAlgoOrder(request));
    }

    /**
     * 合约网格平仓
     * 只有处于已停止未平仓状态合约网格可使用该接口
     *
     * @param request request
     * @return ClosePositionForContractGridRsp
     */
    public OkxRestResponse<ClosePositionForContractGridRsp> closePositionForContractGrid(ClosePositionForContractGridReq request) {
        return execute(api.closePositionForContractGrid(request));
    }

    /**
     * 撤销合约网格平仓单
     *
     * @param request request
     * @return CancelClosePositionOrderForContractGridRsp
     */
    public OkxRestResponse<CancelClosePositionOrderForContractGridRsp> cancelClosePositionForContractGrid(CancelClosePositionOrderForContractGridReq request) {
        return execute(api.cancelClosePositionForContractGrid(request));
    }

    /**
     * 网格策略立即触发
     *
     * @param request request
     * @return InstantTriggerGridAlgoOrderRsp
     */
    public OkxRestResponse<InstantTriggerGridAlgoOrderRsp> instantTriggerGridAlgoOrder(InstantTriggerGridAlgoOrderReq request) {
        return execute(api.instantTriggerGridAlgoOrder(request));
    }

    /**
     * 获取未完成网格策略委托单列表
     *
     * @param algoOrdType 策略订单类型
     *                    grid：现货网格委托
     *                    contract_grid：合约网格委托
     * @param algoId      策略订单ID
     * @param instId      产品ID，如BTC-USDT
     * @param instType    产品类型
     *                    SPOT：币币
     *                    MARGIN：杠杆
     *                    FUTURES：交割合约
     *                    SWAP：永续合约
     * @param after       请求此ID之前（更旧的数据）的分页内容，传的值为对应接口的algoId
     * @param before      请求此ID之后（更新的数据）的分页内容，传的值为对应接口的algoId
     * @param limit       返回结果的数量，最大为100，默认100条
     * @return GridAlgoOrder
     */
    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrderList(
            AlgoOrderType algoOrdType,
            String algoId,
            String instId,
            InstrumentType instType,
            String after,
            String before,
            Integer limit) {
        return execute(api.getGridAlgoOrderList(algoOrdType.getValue(),
                algoId,
                instId,
                instType != null ? instType.getValue() : null,
                after,
                before,
                limit));
    }

    /**
     * 获取历史网格策略委托单列表
     *
     * @param algoOrdType 策略订单类型
     *                    grid：现货网格委托
     *                    contract_grid：合约网格委托
     * @param algoId      策略订单ID
     * @param instId      产品ID，如BTC-USDT
     * @param instType    产品类型
     *                    SPOT：币币
     *                    MARGIN：杠杆
     *                    FUTURES：交割合约
     *                    SWAP：永续合约
     * @param after       请求此ID之前（更旧的数据）的分页内容，传的值为对应接口的algoId
     * @param before      请求此ID之后（更新的数据）的分页内容，传的值为对应接口的algoId
     * @param limit       返回结果的数量，最大为100，默认100条
     * @return GridAlgoOrder
     */
    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrderHistory(
            AlgoOrderType algoOrdType,
            String algoId,
            String instId,
            InstrumentType instType,
            String after,
            String before,
            Integer limit) {
        return execute(api.getGridAlgoOrderHistory(algoOrdType.getValue(),
                algoId,
                instId,
                instType != null ? instType.getValue() : null,
                after,
                before,
                limit));
    }

    /**
     * 获取网格策略委托订单详情
     *
     * @param algoOrdType 策略订单类型
     *                    grid：现货网格委托
     *                    contract_grid：合约网格委托
     * @param algoId      策略订单ID
     * @return GridAlgoOrder
     */
    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrderDetails(AlgoOrderType algoOrdType, String algoId) {
        return execute(api.getGridAlgoOrderDetails(algoOrdType.getValue(), algoId));
    }

    /**
     * 获取网格策略委托子订单信息
     *
     * @param algoOrdType 策略订单类型
     *                    grid：现货网格委托
     *                    contract_grid：合约网格委托
     * @param algoId      策略订单ID
     * @param type        子订单状态
     *                    live：未成交
     *                    filled：已成交
     * @param groupId     组ID
     * @param after       请求此ID之前（更旧的数据）的分页内容，传的值为对应接口的ordId
     * @param before      请求此ID之后（更新的数据）的分页内容，传的值为对应接口的ordId
     * @param limit       返回结果的数量，最大为100，默认100条
     * @return GridAlgoSubOrder
     */
    public OkxRestResponse<GridAlgoSubOrder> getGridAlgoSubOrders(
            AlgoOrderType algoOrdType,
            String algoId,
            String type,
            String groupId,
            String after,
            String before,
            Integer limit) {
        return execute(api.getGridAlgoSubOrders(algoOrdType.getValue(), algoId, type, groupId, after, before, limit));
    }

    /**
     * 获取网格策略委托持仓
     *
     * @param algoOrdType 订单类型
     *                    contract_grid：合约网格委托
     * @param algoId      策略订单ID
     * @return GridAlgoOrderPosition
     */
    public OkxRestResponse<GridAlgoOrderPosition> getGridAlgoOrderPositions(AlgoOrderType algoOrdType, String algoId) {
        return execute(api.getGridAlgoOrderPositions(algoOrdType.getValue(), algoId));
    }

    /**
     * 现货网格提取利润
     *
     * @param request request
     * @return SpotGridWithdrawIncomeRsp
     */
    public OkxRestResponse<SpotGridWithdrawIncomeRsp> spotGridWithdrawIncome(SpotGridWithdrawIncomeReq request) {
        return execute(api.spotGridWithdrawIncome(request));
    }

    /**
     * 调整保证金计算
     *
     * @param request request
     * @return ComputeGridMarginBalanceRsp
     */
    public OkxRestResponse<ComputeGridMarginBalanceRsp> computeGridMarginBalance(ComputeGridMarginBalanceReq request) {
        return execute(api.computeGridMarginBalance(request));
    }

    /**
     * 调整保证金
     *
     * @param request request
     * @return AdjustGridMarginBalanceRsp
     */
    public OkxRestResponse<AdjustGridMarginBalanceRsp> adjustGridMarginBalance(AdjustGridMarginBalanceReq request) {
        return execute(api.adjustGridMarginBalance(request));
    }

    /**
     * 加仓
     * 该接口用于加仓，仅适用于合约网格。
     *
     * @param request request
     * @return AddGridInvestmentRsp
     */
    public OkxRestResponse<AddGridInvestmentRsp> addGridInvestment(AddGridInvestmentReq request) {
        return execute(api.addGridInvestment(request));
    }

    /**
     * 网格策略智能回测（公共）
     * 公共接口无须鉴权
     *
     * @param algoOrdType 策略订单类型
     *                    grid：现货网格委托
     *                    contract_grid：合约网格委托
     * @param instId      产品ID，如BTC-USDT
     * @param direction   合约网格类型
     *                    long：做多，short：做空，neutral：中性
     *                    合约网格必填
     * @param duration    回测周期
     *                    7D：7天，30D：30天，180D：180天
     *                    默认现货网格为7D
     *                    合约网格只支持7D
     * @return GridApiParam
     */
    public OkxRestResponse<GridApiParam> getGridAiParameterPublic(
            AlgoOrderType algoOrdType,
            String instId,
            ContractDirection direction,
            String duration) {
        return execute(api.getGridAiParameterPublic(algoOrdType.getValue(),
                instId,
                direction != null ? direction.getValue() : null,
                duration));
    }

    /**
     * 计算最小投资数量（公共）
     * 公共接口无须鉴权
     *
     * @param request request
     * @return ComputeGridMinInvestmentRsp
     */
    public OkxRestResponse<ComputeGridMinInvestmentRsp> computeGridMinInvestmentPublic(ComputeGridMinInvestmentReq request) {
        return execute(api.computeGridMinInvestmentPublic(request));
    }

    /**
     * RSI回测（公共）
     * 公共接口无须鉴权
     *
     * @param instId      产品ID，如BTC-USDT
     *                    适用于币币
     * @param timeframe   K线种类
     *                    3m, 5m, 15m, 30m (m代表分钟)
     *                    1H, 4H (H代表小时)
     *                    1D (D代表天)
     * @param thold       阈值
     *                    取值[1,100]的整数
     * @param timePeriod  周期
     *                    14
     * @param triggerCond 触发条件
     *                    cross_up：上穿
     *                    cross_down：下穿
     *                    above：上方
     *                    below：下方
     *                    cross：交叉
     *                    默认是cross_down
     * @param duration    回测周期
     *                    1M：1个月
     *                    默认1M
     * @return RsiBackTesting
     */
    public OkxRestResponse<RsiBackTesting> getRsiBackTestingPublic(
            String instId,
            String timeframe,
            Integer thold,
            Integer timePeriod,
            TriggerCondition triggerCond,
            String duration) {
        return execute(api.getRsiBackTestingPublic(instId,
                timeframe,
                thold,
                timePeriod,
                triggerCond != null ? triggerCond.getValue() : null,
                duration));
    }
}