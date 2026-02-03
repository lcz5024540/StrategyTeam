package com.metaverse.coin.strategy.common.model.binance.entity.futures;

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
public class BinanceFuturesOrder {
    private String clientOrderId;
    private BigDecimal cumQty;
    private BigDecimal cumQuote;
    private BigDecimal executedQty;
    private Long orderId;
    private BigDecimal avgPrice;
    private BigDecimal origQty;
    private BigDecimal price;
    private Boolean reduceOnly;
    private String side;
    private String positionSide;
    private String status;
    private BigDecimal stopPrice;
    private Boolean closePosition;
    private String symbol;
    private String timeInForce;
    private String type;
    private String origType;
    private BigDecimal activatePrice;
    private BigDecimal priceRate;
    private Long updateTime;
    private String workingType;
    private Boolean priceProtect;
}
