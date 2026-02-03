package com.metaverse.coin.strategy.common.model.binance.entity.futures;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Binance Futures Kline/Candlestick Data
 * <br>
 * Response Format:
 * [
 *   1499040000000,      // Open time
 *   "0.01634790",       // Open
 *   "0.80000000",       // High
 *   "0.01575800",       // Low
 *   "0.01577100",       // Close
 *   "148976.11427815",  // Volume
 *   1499644799999,      // Close time
 *   "2434.19055334",    // Quote asset volume
 *   308,                // Number of trades
 *   "1756.87402397",    // Taker buy base asset volume
 *   "28.46694368",      // Taker buy quote asset volume
 *   "17928899.62484339" // Ignore
 * ]
 */
@Data
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({
    "openTime",
    "open",
    "high",
    "low",
    "close",
    "volume",
    "closeTime",
    "quoteAssetVolume",
    "numberOfTrades",
    "takerBuyBaseAssetVolume",
    "takerBuyQuoteAssetVolume",
    "ignore"
})
public class BinanceFuturesKline {
    private Long openTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;
    private Long closeTime;
    private BigDecimal quoteAssetVolume;
    private Long numberOfTrades;
    private BigDecimal takerBuyBaseAssetVolume;
    private BigDecimal takerBuyQuoteAssetVolume;
    private String ignore;
}
