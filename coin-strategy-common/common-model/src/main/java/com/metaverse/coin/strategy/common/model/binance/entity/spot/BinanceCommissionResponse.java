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
public class BinanceCommissionResponse {
    private String symbol;
    private Commission standardCommission;
    private Commission taxCommission;
    private Discount discount;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Commission {
        private BigDecimal maker;
        private BigDecimal taker;
        private BigDecimal buyer;
        private BigDecimal seller;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Discount {
        private Boolean enabledForAccount;
        private Boolean enabledForSymbol;
        private String discountAsset;
        private BigDecimal discount;
    }
}
