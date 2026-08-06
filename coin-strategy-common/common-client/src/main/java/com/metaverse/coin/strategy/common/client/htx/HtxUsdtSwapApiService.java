package com.metaverse.coin.strategy.common.client.htx;

import com.metaverse.coin.strategy.common.client.htx.rest.HtxRestClient;
import com.metaverse.coin.strategy.common.client.htx.rest.HtxUsdtSwapService;
import lombok.Getter;

/**
 * HTX USDT-margined perpetual swap facade.
 *
 * <p>Swap traffic uses {@code api.hbdm.com}, which must remain separate from the spot
 * {@code api.huobi.pro} client because the signed host is part of the HTX signature.</p>
 */
@Getter
public class HtxUsdtSwapApiService {

    private final HtxRestClient rest;
    private final HtxUsdtSwapService swapService;

    public HtxUsdtSwapApiService(String accessKey, String secretKey, String baseUrl) {
        this(new HtxRestClient(accessKey, secretKey, baseUrl));
    }

    public HtxUsdtSwapApiService(HtxRestClient rest) {
        this.rest = rest;
        this.swapService = new HtxUsdtSwapService(rest);
    }
}
