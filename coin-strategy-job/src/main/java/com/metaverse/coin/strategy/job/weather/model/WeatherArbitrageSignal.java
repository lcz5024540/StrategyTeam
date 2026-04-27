package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class WeatherArbitrageSignal {

    public enum Side { BUY_YES, BUY_NO, PASS }

    private String marketId;
    private String tokenId;
    private Side side;
    private String city;
    private String question;
    private String reason;
    /** Model-computed fair probability for the token being bought */
    private BigDecimal fairValue;
    /** Current market price of the token being bought */
    private BigDecimal marketPrice;
    /** (fairValue - marketPrice) / marketPrice */
    private BigDecimal edge;
    private BigDecimal expectedValue;
    private BigDecimal confidence;
    private int forecastAgeMinutes;
    private int leadHours;
    private String calibrationVersion;
    private String mode;
    /** Amount to stake in USDC (computed by WeatherCapitalPoolService) */
    private BigDecimal stakeUsdc;
}
