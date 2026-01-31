package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.trading.grid.*;

import java.util.List;
import java.util.Map;

public class OkxGridTradingService extends OkxService {

    public OkxGridTradingService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<PlaceGridAlgoOrderRsp> placeGridAlgoOrder(PlaceGridAlgoOrderReq req) {
        return execute(okxApi.placeGridAlgoOrder(req), "Place grid algo order failed");
    }

    public OkxRestResponse<AmendGridAlgoOrderRsp> amendGridAlgoOrder(AmendGridAlgoOrderReq req) {
        return execute(okxApi.amendGridAlgoOrder(req), "Amend grid algo order failed");
    }

    public OkxRestResponse<AddGridInvestmentRsp> addGridInvestment(AddGridInvestmentReq req) {
        return execute(okxApi.addGridInvestment(req), "Add grid investment failed");
    }

    public OkxRestResponse<StopGridAlgoOrderRsp> stopGridAlgoOrder(StopGridAlgoOrderReq req) {
        return execute(okxApi.stopGridAlgoOrder(req), "Stop grid algo order failed");
    }

    public OkxRestResponse<ClosePositionForContractGridRsp> closePositionForContractGrid(ClosePositionForContractGridReq req) {
        return execute(okxApi.closePositionForContractGrid(req), "Close position for contract grid failed");
    }

    public OkxRestResponse<CancelClosePositionOrderForContractGridRsp> cancelClosePositionOrderForContractGrid(CancelClosePositionOrderForContractGridReq req) {
        return execute(okxApi.cancelClosePositionOrderForContractGrid(req), "Cancel close position order for contract grid failed");
    }

    public OkxRestResponse<InstantTriggerGridAlgoOrderRsp> instantTriggerGridAlgoOrder(InstantTriggerGridAlgoOrderReq req) {
        return execute(okxApi.instantTriggerGridAlgoOrder(req), "Instant trigger grid algo order failed");
    }

    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getGridAlgoOrdersHistory(queryMap), "Get grid algo orders history failed");
    }

    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrdersPending(Map<String, Object> queryMap) {
        return execute(okxApi.getGridAlgoOrdersPending(queryMap), "Get grid algo orders pending failed");
    }

    public OkxRestResponse<GridAlgoOrder> getGridAlgoOrderDetails(String algoOrdId, String algoClOrdId) {
        return execute(okxApi.getGridAlgoOrderDetails(algoOrdId, algoClOrdId), "Get grid algo order details failed");
    }

    public OkxRestResponse<GridAlgoSubOrder> getGridAlgoSubOrders(Map<String, Object> queryMap) {
        return execute(okxApi.getGridAlgoSubOrders(queryMap), "Get grid algo sub orders failed");
    }

    public OkxRestResponse<GridAlgoOrderPosition> getGridAlgoPositions(Map<String, Object> queryMap) {
        return execute(okxApi.getGridAlgoPositions(queryMap), "Get grid algo positions failed");
    }

    public OkxRestResponse<SpotGridWithdrawIncomeRsp> spotGridWithdrawIncome(SpotGridWithdrawIncomeReq req) {
        return execute(okxApi.spotGridWithdrawIncome(req), "Spot grid withdraw income failed");
    }

    public OkxRestResponse<ComputeGridMinInvestmentRsp> computeGridMinInvestment(Map<String, Object> queryMap) {
        return execute(okxApi.computeGridMinInvestment(queryMap), "Compute grid min investment failed");
    }

    public OkxRestResponse<ComputeGridMarginBalanceRsp> computeGridMarginBalance(Map<String, Object> queryMap) {
        return execute(okxApi.computeGridMarginBalance(queryMap), "Compute grid margin balance failed");
    }

    public OkxRestResponse<AdjustGridMarginBalanceRsp> adjustGridMarginBalance(AdjustGridMarginBalanceReq req) {
        return execute(okxApi.adjustGridMarginBalance(req), "Adjust grid margin balance failed");
    }

    public OkxRestResponse<GridApiParam> getGridAiParam(Map<String, Object> queryMap) {
        return execute(okxApi.getGridAiParam(queryMap), "Get grid AI param failed");
    }

    public OkxRestResponse<ComputeGridMarginBalanceRsp> computeGridMarginBalancePost(ComputeGridMarginBalanceReq req) {
        return execute(okxApi.computeGridMarginBalancePost(req), "Compute grid margin balance (POST) failed");
    }

    public OkxRestResponse<AmendGridAlgoBasicParamRsp> amendGridAlgoBasicParam(AmendGridAlgoBasicParamReq req) {
        return execute(okxApi.amendGridAlgoBasicParam(req), "Amend grid algo basic param failed");
    }

    public OkxRestResponse<ComputeGridMinInvestmentRsp> computeGridMinInvestmentPost(ComputeGridMinInvestmentReq req) {
        return execute(okxApi.computeGridMinInvestmentPost(req), "Compute grid min investment (POST) failed");
    }

    public OkxRestResponse<RsiBackTestingRsp> rsiBackTesting(Map<String, Object> queryMap) {
        return execute(okxApi.rsiBackTesting(queryMap), "RSI back testing failed");
    }

    public OkxRestResponse<GridQuantityRsp> getGridQuantity(Map<String, Object> queryMap) {
        return execute(okxApi.getGridQuantity(queryMap), "Get grid quantity failed");
    }
}
