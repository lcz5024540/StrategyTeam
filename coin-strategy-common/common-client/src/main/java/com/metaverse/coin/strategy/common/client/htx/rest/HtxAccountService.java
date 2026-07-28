package com.metaverse.coin.strategy.common.client.htx.rest;

import com.alibaba.fastjson2.TypeReference;
import com.metaverse.coin.strategy.common.client.htx.model.HtxAccount;
import com.metaverse.coin.strategy.common.client.htx.model.HtxRestResponse;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * HTX Spot account endpoints (private).
 */
@RequiredArgsConstructor
public class HtxAccountService {

    private final HtxRestClient rest;

    /**
     * List all sub-accounts (spot / margin / otc / point).
     */
    public HtxRestResponse<List<HtxAccount>> listAccounts() {
        return rest.signedGet("/v1/account/accounts", Collections.emptyMap(),
                new TypeReference<HtxRestResponse<List<HtxAccount>>>() {});
    }
}
