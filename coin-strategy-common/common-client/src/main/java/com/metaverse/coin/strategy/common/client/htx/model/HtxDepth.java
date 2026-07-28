package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.Data;

import java.util.List;

/**
 * HTX spot order book snapshot ({@code /market/depth?type=step0}).
 * bids/asks are ordered by best price first.
 */
@Data
public class HtxDepth {
    private long ts;
    private long version;
    private String symbol;
    private List<HtxPriceLevel> bids;
    private List<HtxPriceLevel> asks;
}
