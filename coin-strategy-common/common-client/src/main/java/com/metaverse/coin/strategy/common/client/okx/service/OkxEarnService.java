package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.finance.staking.*;

import java.util.Map;

public class OkxEarnService extends OkxService {

    public OkxEarnService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<OkxStakingOffer> getStakingOffers(Map<String, Object> queryMap) {
        return execute(okxApi.getStakingOffers(queryMap), "Get staking offers failed");
    }

    public OkxRestResponse<OkxStakingPurchaseRsp> purchaseStaking(OkxStakingPurchaseReq req) {
        return execute(okxApi.purchaseStaking(req), "Purchase staking failed");
    }

    public OkxRestResponse<OkxStakingRedeemRsp> redeemStaking(OkxStakingRedeemReq req) {
        return execute(okxApi.redeemStaking(req), "Redeem staking failed");
    }

    public OkxRestResponse<OkxStakingCancelRsp> cancelStaking(OkxStakingCancelReq req) {
        return execute(okxApi.cancelStaking(req), "Cancel staking failed");
    }

    public OkxRestResponse<OkxStakingOrder> getActiveStakingOrders(Map<String, Object> queryMap) {
        return execute(okxApi.getActiveStakingOrders(queryMap), "Get active staking orders failed");
    }

    public OkxRestResponse<OkxStakingOrder> getStakingOrdersHistory(Map<String, Object> queryMap) {
        return execute(okxApi.getStakingOrdersHistory(queryMap), "Get staking orders history failed");
    }
}
