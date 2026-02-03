package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceFuturesOrderBook {
    private Long lastUpdateId;
    
    @JsonProperty("E")
    private Long messageOutputTime;
    
    @JsonProperty("T")
    private Long transactionTime;
    
    private List<List<String>> bids;
    private List<List<String>> asks;
}
