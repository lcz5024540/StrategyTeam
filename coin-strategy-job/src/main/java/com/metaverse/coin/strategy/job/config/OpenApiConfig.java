package com.metaverse.coin.strategy.job.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartWalletOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Coin Strategy Smart Wallet API")
                .description("Polymarket smart-wallet discovery, paper trading, risk control, accounting, settlement, and analytics APIs.")
                .version("v1")
                .contact(new Contact().name("CoinStrategy")));
    }
}
