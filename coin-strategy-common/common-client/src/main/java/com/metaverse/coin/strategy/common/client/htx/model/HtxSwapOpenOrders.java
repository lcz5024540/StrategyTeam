package com.metaverse.coin.strategy.common.client.htx.model;

import lombok.Data;

import java.util.List;

/** Current unfilled isolated USDT-margined swap orders. */
@Data
public class HtxSwapOpenOrders {

    private List<HtxSwapOrder> orders;
    private Integer totalPage;
    private Integer currentPage;
    private Integer totalSize;
}
