package com.metaverse.coin.strategy.htx.config;

import com.metaverse.coin.strategy.common.client.htx.HtxUsdtSwapApiService;
import com.metaverse.coin.strategy.htx.service.HtxTradeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the USDT-margined contract client only when the mode and credentials are present. */
@Configuration
@ConditionalOnProperty(prefix = "htx.contract", name = "enabled", havingValue = "true")
@ConditionalOnExpression("'${htx.api.access-key:}' != '' && '${htx.api.secret-key:}' != ''")
@EnableConfigurationProperties({HtxContractProperties.class, HtxTrendProperties.class})
public class HtxContractConfig {

    @Bean
    public HtxUsdtSwapApiService htxUsdtSwapApiService(HtxTradeProperties tradeProperties,
                                                        HtxContractProperties contractProperties) {
        return new HtxUsdtSwapApiService(
                tradeProperties.getApi().getAccessKey(),
                tradeProperties.getApi().getSecretKey(),
                contractProperties.getBaseUrl(),
                contractProperties.getUidQueryLimit(),
                contractProperties.getUidTradeLimit(),
                contractProperties.getUidRateWindowMs());
    }
}
