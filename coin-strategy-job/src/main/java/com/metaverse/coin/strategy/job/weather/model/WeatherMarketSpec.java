package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Parsed structure of a Polymarket weather market.
 */
@Data
@Builder
public class WeatherMarketSpec {

    public enum TemperatureType { MAX, MIN, AVG }

    /** Polymarket conditionId */
    private String marketId;
    /** YES token id */
    private String tokenId;
    /** Raw market question */
    private String question;
    private String city;
    private TemperatureType temperatureType;
    /** Temperature threshold in Fahrenheit extracted from the question */
    private BigDecimal thresholdF;
    /** Current YES token mid-price [0,1] */
    private BigDecimal yesPrice;
    /** Current NO token mid-price */
    private BigDecimal noPrice;
    /** Available liquidity in USDC */
    private BigDecimal liquidityUsdc;
    /** Unix epoch seconds of market resolution */
    private long endTimestampSeconds;
}
