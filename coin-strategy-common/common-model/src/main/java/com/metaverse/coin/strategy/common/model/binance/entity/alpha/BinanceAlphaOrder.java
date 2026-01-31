package com.metaverse.coin.strategy.common.model.binance.entity.alpha;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceAlphaOrder {
    private Long algoId;
    private String clientAlgoId;
    private String symbol;
    private String side;
    private String algoType; // VP, TWAP
    private String urgency;
    private BigDecimal totalQty;
    private BigDecimal executedQty;
    private BigDecimal executedAmt;
    private BigDecimal avgPrice;
    private String algoStatus; // WORKING, CANCELLED, FINISHED
    private Long bookTime;
    private Long endTime;
    private Boolean success; // For response
    private String code;     // For response
    private String msg;      // For response
}
