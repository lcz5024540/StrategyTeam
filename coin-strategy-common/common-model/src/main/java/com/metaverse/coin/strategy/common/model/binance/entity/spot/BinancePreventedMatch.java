package com.metaverse.coin.strategy.common.model.binance.entity.spot;

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
public class BinancePreventedMatch {
    private String symbol;
    private Long preventedMatchId;
    private Long takerOrderId;
    private Long makerOrderId;
    private BigDecimal tradeGroupId;
    private Long selfTradePreventionMode;
    private BigDecimal price;
    private BigDecimal makerPreventedQuantity;
    private Long transactTime;
}
