package com.metaverse.coin.strategy.common.client.sunwk;

import com.metaverse.coin.strategy.common.client.sunwk.service.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class SunwkApiService {

    private final SunwkApi sunwkApi;
    
    private final SunwkAccountService accountService;
    private final SunwkOrderService orderService;
    private final SunwkPositionService positionService;
    private final SunwkMarketDataService marketDataService;
    private final SunwkWalletService walletService;

    public SunwkApiService(SunwkApi sunwkApi) {
        this.sunwkApi = sunwkApi;
        
        this.accountService = new SunwkAccountService(sunwkApi);
        this.orderService = new SunwkOrderService(sunwkApi);
        this.positionService = new SunwkPositionService(sunwkApi);
        this.marketDataService = new SunwkMarketDataService(sunwkApi);
        this.walletService = new SunwkWalletService(sunwkApi);
    }
}
