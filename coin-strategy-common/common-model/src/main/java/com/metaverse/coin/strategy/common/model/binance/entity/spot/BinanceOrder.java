package com.metaverse.coin.strategy.common.model.binance.entity.spot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BinanceOrder {
    private String symbol;
    private Long orderId;
    private Long orderListId;
    private String clientOrderId;
    private BigDecimal price;
    private BigDecimal origQty;
    private BigDecimal executedQty;
    private BigDecimal cummulativeQuoteQty;
    private String status;
    private String timeInForce;
    private String type;
    private String side;
    private BigDecimal stopPrice;
    private BigDecimal icebergQty;
    private Long time;
    private Long updateTime;
    private Boolean isWorking;
    private Long workingTime;
    private BigDecimal origQuoteOrderQty;
    private String selfTradePreventionMode;
    private String origClientOrderId; // Returned in Cancel Order response
    private Long transactTime; // Returned in New Order response
    private Long trailingDelta;
    private Long trailingTime;
    private Long strategyId;
    private Long strategyType;
    private Long preventedMatchId;
    private BigDecimal preventedQty;
    private List<Fill> fills;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Fill {
        private BigDecimal price;
        private BigDecimal qty;
        private BigDecimal commission;
        private String commissionAsset;
        private Long tradeId;
    }
}
