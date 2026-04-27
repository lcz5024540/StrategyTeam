package com.metaverse.coin.strategy.job.config;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("'${binance.api.api-key:}' != '' && '${binance.api.secret-key:}' != ''")
public class BinanceConfig {

    @Value("${binance.api.api-key}")
    private String apiKey;

    @Value("${binance.api.secret-key}")
    private String secretKey;

    @Value("${binance.api.spot-url:https://api.binance.com}")
    private String spotUrl;

    @Value("${binance.api.futures-url:https://fapi.binance.com}")
    private String futuresUrl;

    @Value("${binance.api.proxy-host:}")
    private String proxyHost;

    @Value("${binance.api.proxy-port:}")
    private int proxyPort;

    @Value("${binance.api.proxy-type:}")
    private String proxyType;

    @Value("${binance.api.proxy-username:}")
    private String proxyUsername;

    @Value("${binance.api.proxy-password:}")
    private String proxyPassword;

    @Bean
    public BinanceApiService binanceApiService() {
        return new BinanceApiService(
                apiKey,
                secretKey,
                spotUrl,
                futuresUrl,
                proxyHost,
                proxyPort,
                proxyType,
                proxyUsername,
                proxyPassword
        );
    }
}
