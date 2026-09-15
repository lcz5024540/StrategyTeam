package com.metaverse.coin.strategy.common.client.htx.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;

/** One HTX USDT-margined swap kline bar returned by market/history/kline. */
@Data
public class HtxSwapKline {
    /** Bar open time in seconds. */
    private Long id;
    private BigDecimal open;
    private BigDecimal close;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal amount;
    private BigDecimal vol;
    @JSONField(name = "trade_turnover")
    private BigDecimal tradeTurnover;
    private Long count;
}
