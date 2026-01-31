package com.metaverse.coin.strategy.common.model.binance.entity.margin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceMarginBorrowRepayRecord {
    private Long txId;
    private Long tranId; // Sometimes returned as tranId
    private String asset;
    private String symbol; // Isolated margin
    private BigDecimal principal;
    private BigDecimal amount; // Repay amount
    private BigDecimal interest;
    private String status;
    private Long timestamp;
    private String type; // BORROW or REPAY
    private String isIsolated;
}
