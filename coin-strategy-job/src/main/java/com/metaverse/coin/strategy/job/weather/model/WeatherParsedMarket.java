package com.metaverse.coin.strategy.job.weather.model;

import com.metaverse.coin.strategy.job.smartwallet.service.PolymarketApiClient;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WeatherParsedMarket {
    private PolymarketApiClient.MarketMetadata marketMetadata;
    private WeatherMarketSpec spec;
}
