package com.metaverse.coin.strategy.common.model.binance.entity.spot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class BinanceAccountInfo {

    @JsonProperty("makerCommission")
    private Integer makerCommission;

    @JsonProperty("takerCommission")
    private Integer takerCommission;

    @JsonProperty("buyerCommission")
    private Integer buyerCommission;

    @JsonProperty("sellerCommission")
    private Integer sellerCommission;

    @JsonProperty("commissionRates")
    private CommissionRates commissionRates;

    @JsonProperty("canTrade")
    private Boolean canTrade;

    @JsonProperty("canWithdraw")
    private Boolean canWithdraw;

    @JsonProperty("canDeposit")
    private Boolean canDeposit;

    @JsonProperty("brokered")
    private Boolean brokered;

    @JsonProperty("requireSelfTradePrevention")
    private Boolean requireSelfTradePrevention;

    @JsonProperty("preventSor")
    private Boolean preventSor;

    @JsonProperty("updateTime")
    private Long updateTime;

    @JsonProperty("accountType")
    private String accountType;

    @JsonProperty("balances")
    private List<BinanceBalance> balances;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommissionRates {
        @JsonProperty("maker")
        private BigDecimal maker;

        @JsonProperty("taker")
        private BigDecimal taker;

        @JsonProperty("buyer")
        private BigDecimal buyer;

        @JsonProperty("seller")
        private BigDecimal seller;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BinanceBalance {
        @JsonProperty("asset")
        private String asset;

        @JsonProperty("free")
        private BigDecimal free;

        @JsonProperty("locked")
        private BigDecimal locked;
    }
}
