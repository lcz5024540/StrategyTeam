package com.metaverse.coin.strategy.common.client.okx.service;

import com.metaverse.coin.strategy.common.client.okx.OkxApi;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.OkxRestResponse;
import com.metaverse.coin.strategy.common.model.okx.entity.rest.affiliate.OkxAffiliateInviteeDetail;

import java.util.Map;

public class OkxAffiliateService extends OkxService {

    public OkxAffiliateService(OkxApi okxApi) {
        super(okxApi);
    }

    public OkxRestResponse<OkxAffiliateInviteeDetail> getAffiliateInviteeDetail(Map<String, Object> queryMap) {
        return execute(okxApi.getAffiliateInviteeDetail(queryMap), "Get affiliate invitee detail failed");
    }
}
