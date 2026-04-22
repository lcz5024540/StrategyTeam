package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class WeatherArbitrageSignal {

    public enum Side { BUY_YES, BUY_NO }

    private String marketId;
    private String tokenId;
    private Side side;
    private String city;
    private String question;
    /** Model-computed fair probability for the token being bought */
    private BigDecimal fairValue;
    /** Current market price of the token being bought */
    private BigDecimal marketPrice;
    /** (fairValue - marketPrice) / marketPrice */
    private BigDecimal edge;
    /** Amount to stake in USDC (computed by WeatherCapitalPoolService) */
    private BigDecimal stakeUsdc;
}
