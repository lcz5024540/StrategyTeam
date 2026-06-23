package com.metaverse.coin.strategy.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 轻量版 single-exchange spot/futures 参数视图。
 * 仅暴露 web 端在 strategy 查询场景需要的两个字段，与 core 中默认值保持一致。
 */
@Component
public class SingleExchangeSpotFuturesProperties {

    private final Integer leverage;
    private final BigDecimal openThreshold;

    public SingleExchangeSpotFuturesProperties(
            @Value("${arbitrage.spot-futures.leverage:3}") Integer leverage,
            @Value("${arbitrage.spot-futures.open-threshold:0.0005}") BigDecimal openThreshold) {
        this.leverage = leverage;
        this.openThreshold = openThreshold;
    }

    public Integer getLeverage() {
        return leverage;
    }

    public BigDecimal getOpenThreshold() {
        return openThreshold;
    }
}
