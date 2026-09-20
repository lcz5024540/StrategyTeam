package com.metaverse.coin.strategy.htx.config;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.htx.service.HtxTradeAccountService;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the primary USDT-margined contract client from DB account records, with env fallback. */
@Configuration
@ConditionalOnProperty(prefix = "htx.contract", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({
        HtxContractProperties.class,
        HtxTrendProperties.class,
        HtxTrendEnsembleShadowProperties.class,
        HtxTradeProperties.class
})
public class HtxContractConfig {

    @Bean
    public HtxUsdtSwapApiService htxUsdtSwapApiService(HtxTradeProperties tradeProperties,
                                                        HtxContractProperties contractProperties,
                                                        HtxTradeAccountService accountService) {
        return accountService.primarySwapApiService(tradeProperties, contractProperties);
    }
}
