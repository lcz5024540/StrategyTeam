package com.metaverse.coin.strategy.common.model.binance.entity.spot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceOrderCountUsage {
    private String rateLimitType;
    private String interval;
    private Integer intervalNum;
    private Integer limit;
    private Integer count;
}
