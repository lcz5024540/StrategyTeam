package com.metaverse.coin.strategy.job.weather.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WeatherRunStats {
    private int scannedMarkets;
    private int parsedMarkets;
    private int actionableSignals;
    private int paperTradesOpened;
}
