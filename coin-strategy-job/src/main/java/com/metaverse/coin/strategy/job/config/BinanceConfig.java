package com.metaverse.coin.strategy.job.config;

import com.metaverse.coin.strategy.common.client.binance.BinanceApiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BinanceConfig {

    @Value("${binance.api.api-key}")
    private String apiKey;

    @Value("${binance.api.secret-key}")
    private String secretKey;

    @Value("${binance.api.spot-url:https://api.binance.com}")
    private String spotUrl;

    @Value("${binance.api.futures-url:https://fapi.binance.com}")
    private String futuresUrl;

    @Bean
    public BinanceApiService binanceApiService() {
        return new BinanceApiService(apiKey, secretKey, spotUrl, futuresUrl);
    }
}
