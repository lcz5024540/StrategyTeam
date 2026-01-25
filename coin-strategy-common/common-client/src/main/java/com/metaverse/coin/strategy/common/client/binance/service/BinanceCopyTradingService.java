package com.metaverse.coin.strategy.common.client.binance.service;

import com.binance.connector.client.SpotClient;
import java.util.LinkedHashMap;

/**
 * Service for Copy Trading
 * Endpoints: /sapi/v1/copyTrading/...
 */
public class BinanceCopyTradingService {
    private final SpotClient spotClient;

    public BinanceCopyTradingService(SpotClient spotClient) {
        this.spotClient = spotClient;
    }

    // Similar to Algo, Copy Trading is a newer addition. 
    // I will check if 'createCopyTrading' exists or similar.
    // If not, I will leave it as a placeholder for structure.
}
